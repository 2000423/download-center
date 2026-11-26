// utils/render3d.js —— WebGL 3D 城市渲染（城市天际线式自由视角）
//
// 为什么重写渲染层：2:1 等距 + 固定朝向的立面贴图，只能做 90° 整数倍旋转（render.js）。
// 用户要「像城市天际线一样」按住滚轮任意角度旋转，唯一路线是 3D 渲染：
//   * 地块 / 道路 = 顶点色平面（一次合并 VBO）
//   * 建筑 = 盒子体块，四个侧面贴现有立面 PNG（拼成一张 atlas，保住现代风格美术）
//   * 车辆 / 行人 = 动态小盒子（每帧重建，数量少）
//   * 相机 = 轨道相机：yaw 任意、pitch 25°~80°、dist 缩放、target 平移
//   * 拾取 = 屏幕射线与地面求交（不再需要 toGrid 反解 / sum 深度排序）
//
// 两端共用本文件：小程序 require（module.exports），预览页 <script src>（window.Render3D）。
// 不依赖 sim.js / wx / DOM；贴图加载与 atlas 画布由页面层提供。

// ---- 常量（高度口径与 utils/render.js 一致）----
var TILE_W = 96, TILE_H = 48;
// 3D 专用体量（2026-11-23 用户要求）：**低密度住宅**矮一档（48→36）+ 三角屋顶，高密度再高一半（96→144）。
// 与 2D render.js 的 BUILD_HEIGHT（48/72/96，与贴图 PNG 高度双锁）**故意不同**：贴图 UV 竖向
// 只是被拉伸/压缩，风格不破坏；2D 降级路径维持原贴图高度不变。
var BUILD_HEIGHT = { 1: 48, 2: 72, 3: 144 };
var RES_L1_HEIGHT = 36;   // 低密度住宅专用体高（三角小屋），其余低密度保持 48 平顶
var SERVICE_HEIGHT = { utility: 56, police: 56, fire: 56, clinic: 48, edu1: 44, edu2: 64, edu3: 96, waste: 36 };
var FOOT_RATIO = 0.72;          // 建筑底面边长（格）
var HIGHWAY_Y = 0;   // 高速边偏移；2026-11-23 四边高速：y=0 / y=SIZE-1 / x=0 / x=SIZE-1
                     // （isHighwayEdge 在工厂作用域内定义，用到 size）
// 3D 体块高度放大系数：贴图里的 48/72/96 px 高度是「2D 立面图」的画布高度，
// 直接换算成格只有 0.5~1 格（像薄片）。3D 里按 2.4 倍拉高，才有城市天际线的体量感；
// 贴图竖向拉伸只是窗带变高，不破坏风格。
var HEIGHT_SCALE = 2.4;

function buildingHeight(type, level) {
  if (SERVICE_HEIGHT[type] !== undefined) return SERVICE_HEIGHT[type];
  var lv = Math.round(level);
  if (!(lv >= 1)) lv = 1;           // 兼容 undefined / NaN：兜底 L1，避免高度算出 NaN
  if (lv > 3) lv = 3;
  if (type === 'res' && lv === 1) return RES_L1_HEIGHT;   // 低密度住宅：三角小屋（2026-11-23）
  return BUILD_HEIGHT[lv];
}

// 建筑顶面颜色：住宅=绿屋顶（保住「住宅=绿」识别色）；服务建筑各给专属顶色
// （2026-11-23 用户反馈「服务房型跟其他的太像」——俯视时顶色是最先被看到的识别特征）
var TOP_COLORS = {
  res: [0.31, 0.66, 0.46],
  utility: [0.36, 0.62, 0.68],   // 水电：青（同水塔罐体色）
  police: [0.26, 0.33, 0.52],    // 警察：藏蓝
  fire: [0.58, 0.28, 0.24],      // 消防：砖红
  clinic: [0.78, 0.84, 0.82],    // 诊所：浅白绿（红十字招牌俯视也可见）
  edu1: [0.62, 0.52, 0.34],      // 小学：暖黄褐
  edu2: [0.54, 0.44, 0.30],      // 中学：深黄褐
  edu3: [0.42, 0.46, 0.52],      // 大学：石板蓝灰
  waste: [0.44, 0.45, 0.30],     // 垃圾场：橄榄绿
  def: [0.29, 0.31, 0.33],
};
// 服务建筑屋顶构筑物高度（格单位，超出建筑体高的部分）：
// 参与 pick 楼体遮挡判定，让屋顶水塔/钟塔也能被鼠标「点到」
var ROOF_EXTRA = {
  utility: 0.46, police: 0.16, fire: 0.40, clinic: 0.12,
  edu1: 0.10, edu2: 0.10, edu3: 0.52, waste: 0.14,
};
// 地面配色（比 2D 贴图提亮一档：3D 有透视阴影衰减，太深会糊成一片黑）
var GROUND_COLORS = {
  grass: [0.30, 0.45, 0.34],     // #4C7357
  road: [0.58, 0.62, 0.69],      // #949EB0
  highway: [0.30, 0.35, 0.43],   // #4D596E
  park: [0.40, 0.78, 0.50],      // #66C780
  parking: [0.31, 0.36, 0.44],   // #4F5C70
  cemetery: [0.64, 0.71, 0.65],  // #A3B5A6
  line: [0.93, 0.95, 0.97],      // 白色分隔线
  soil: [0.44, 0.36, 0.28],      // 地层剖面（底座侧面）
  slab: [0.24, 0.37, 0.28],      // 底座顶面（比草地略深）
};
// 天空渐变（顶部深、地平线浅）——城市天际线那种淡蓝天空
var SKY_TOP = [0.33, 0.50, 0.68], SKY_BOTTOM = [0.76, 0.85, 0.92];

// ---------------------------------------------------------------- 4x4 矩阵
function m4Identity() {
  return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
}
function m4Multiply(a, b) {   // a * b（列主序）
  var out = new Array(16);
  for (var c = 0; c < 4; c++) {
    for (var r = 0; r < 4; r++) {
      var s = 0;
      for (var k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
      out[c * 4 + r] = s;
    }
  }
  return out;
}
function m4Perspective(fovy, aspect, near, far) {
  var f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
  return [f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) * nf, -1, 0, 0, 2 * far * near * nf, 0];
}
function m4LookAt(eye, center, up) {
  var zx = eye[0] - center[0], zy = eye[1] - center[1], zz = eye[2] - center[2];
  var zl = Math.sqrt(zx * zx + zy * zy + zz * zz) || 1;
  zx /= zl; zy /= zl; zz /= zl;
  var xx = up[1] * zz - up[2] * zy, xy = up[2] * zx - up[0] * zz, xz = up[0] * zy - up[1] * zx;
  var xl = Math.sqrt(xx * xx + xy * xy + xz * xz) || 1;
  xx /= xl; xy /= xl; xz /= xl;
  var yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
  return [xx, yx, zx, 0, xy, yy, zy, 0, xz, yz, zz, 0,
    -(xx * eye[0] + xy * eye[1] + xz * eye[2]),
    -(yx * eye[0] + yy * eye[1] + yz * eye[2]),
    -(zx * eye[0] + zy * eye[1] + zz * eye[2]), 1];
}
function m4Invert(m) {        // 通用 4x4 逆矩阵（gl-matrix 算法）
  var a00 = m[0], a01 = m[1], a02 = m[2], a03 = m[3];
  var a10 = m[4], a11 = m[5], a12 = m[6], a13 = m[7];
  var a20 = m[8], a21 = m[9], a22 = m[10], a23 = m[11];
  var a30 = m[12], a31 = m[13], a32 = m[14], a33 = m[15];
  var b00 = a00 * a11 - a01 * a10, b01 = a00 * a12 - a02 * a10;
  var b02 = a00 * a13 - a03 * a10, b03 = a01 * a12 - a02 * a11;
  var b04 = a01 * a13 - a03 * a11, b05 = a02 * a13 - a03 * a12;
  var b06 = a20 * a31 - a21 * a30, b07 = a20 * a32 - a22 * a30;
  var b08 = a20 * a33 - a23 * a30, b09 = a21 * a32 - a22 * a31;
  var b10 = a21 * a33 - a23 * a31, b11 = a22 * a33 - a23 * a32;
  var det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
  if (!det) return null;
  det = 1 / det;
  return [
    (a11 * b11 - a12 * b10 + a13 * b09) * det, (a02 * b10 - a01 * b11 - a03 * b09) * det,
    (a31 * b05 - a32 * b04 + a33 * b03) * det, (a22 * b04 - a21 * b05 - a23 * b03) * det,
    (a12 * b08 - a10 * b11 - a13 * b07) * det, (a00 * b11 - a02 * b08 + a03 * b07) * det,
    (a32 * b02 - a30 * b05 - a33 * b01) * det, (a20 * b05 - a22 * b02 + a23 * b01) * det,
    (a10 * b10 - a11 * b08 + a13 * b06) * det, (a01 * b08 - a00 * b10 - a03 * b06) * det,
    (a30 * b04 - a31 * b02 + a33 * b00) * det, (a21 * b02 - a20 * b04 - a23 * b00) * det,
    (a11 * b07 - a10 * b09 - a12 * b06) * det, (a00 * b09 - a01 * b07 + a02 * b06) * det,
    (a31 * b01 - a30 * b03 - a32 * b00) * det, (a20 * b03 - a21 * b01 + a22 * b00) * det,
  ];
}

// ---------------------------------------------------------------- 着色器
var VS_SRC = [
  'attribute vec3 aPos;',
  'attribute vec2 aUV;',
  'attribute vec3 aColor;',
  'uniform mat4 uVP;',
  'varying vec2 vUV;',
  'varying vec3 vColor;',
  'void main(){ vUV=aUV; vColor=aColor; gl_Position=uVP*vec4(aPos,1.0); }',
].join('\n');
var FS_SRC = [
  'precision mediump float;',
  'varying vec2 vUV;',
  'varying vec3 vColor;',
  'uniform sampler2D uTex;',
  'uniform vec3 uTint;',
  'uniform float uAlpha;',
  'void main(){',
  '  vec4 t=texture2D(uTex,vUV);',
  '  gl_FragColor=vec4(t.rgb*vColor*uTint, t.a*uAlpha);',
  '}',
].join('\n');

// ---------------------------------------------------------------- 工厂
function createRenderer(opts) {
  var size = opts.size;
  var gl = opts.gl;
  var canvas = opts.canvas;
  var HALF = (size - 1) / 2;
  // 四边高速（2026-11-23）：地图四条外边整圈都是高速路面
  function isHighwayEdge(x, y) {
    return y === HIGHWAY_Y || y === size - 1 || x === HIGHWAY_Y || x === size - 1;
  }
  // 世界坐标：cell (x,y) → (x-HALF, 0, y-HALF)，地图中心在原点

  // 相机（轨道）：yaw 任意、pitch 限位、dist 缩放、target 平移（世界坐标）
  var cam = { tx: 0, tz: -6, yaw: Math.PI * 0.25, pitch: 0.74, dist: 26 };
  var PITCH_MIN = 0.42, PITCH_MAX = 1.45;      // 24° ~ 83°
  var DIST_MIN = 8, DIST_MAX = 70;
  var FOV = 0.82;                               // ~47°

  // atlas：{name: [u0,v0,u1,v1]}（v 已按 flipY 语义：v0=图顶部）
  var atlasRects = null, whiteUV = [0, 0, 0, 0];

  // 静态几何缓冲
  var groundVB = null, groundCount = 0;
  var buildVB = null, buildCount = 0;
  var shadowVB = null, shadowCount = 0;
  var skyVB = null, skyCount = 0;
  // 动态缓冲
  var dynVB = null;
  var hlVB = null;

  var prog = gl.createProgram();
  (function compile() {
    function sh(type, src) {
      var s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        throw new Error('shader 编译失败: ' + gl.getShaderInfoLog(s));
      }
      return s;
    }
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS_SRC));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FS_SRC));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      throw new Error('program 链接失败: ' + gl.getProgramInfoLog(prog));
    }
  })();
  var loc = {
    aPos: gl.getAttribLocation(prog, 'aPos'),
    aUV: gl.getAttribLocation(prog, 'aUV'),
    aColor: gl.getAttribLocation(prog, 'aColor'),
    uVP: gl.getUniformLocation(prog, 'uVP'),
    uTex: gl.getUniformLocation(prog, 'uTex'),
    uTint: gl.getUniformLocation(prog, 'uTint'),
    uAlpha: gl.getUniformLocation(prog, 'uAlpha'),
  };
  var tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  // 1×1 白色占位（atlas 上传前也能画顶点色物体）
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([255, 255, 255, 255]));
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.enable(gl.DEPTH_TEST);
  // 不做背面剔除：本渲染器所有几何都是「封闭盒子 + 朝上的平面」，双面绘制不会穿帮，
  // 反而避免「某类 quad 绕向写反 → 整类物体消失」这种隐性 bug（白线/高亮/楼影都踩过）。
  gl.disable(gl.CULL_FACE);
  gl.clearColor(0.76, 0.85, 0.92, 1);   // 天空底色（真正的天空是渐变 quad，见 buildSky）

  function bindAttribs() {
    gl.enableVertexAttribArray(loc.aPos);
    gl.enableVertexAttribArray(loc.aUV);
    gl.enableVertexAttribArray(loc.aColor);
    gl.vertexAttribPointer(loc.aPos, 3, gl.FLOAT, false, 32, 0);
    gl.vertexAttribPointer(loc.aUV, 2, gl.FLOAT, false, 32, 12);
    gl.vertexAttribPointer(loc.aColor, 3, gl.FLOAT, false, 32, 20);
  }

  // ---- atlas：程序化绘制「平立面」贴图 ----
  // 为什么不用 assets/sprites 的立面 PNG：那套图是等距渲染图，顶部是屋顶菱形、
  // 墙面是斜投影，贴到 3D 盒体侧面会长出大块绿色三角、窗带歪斜。3D 需要正交立面，
  // 所以这里用 2D canvas 现画：墙色 + 玻璃窗带 + 女儿墙 + 底层基座，按类型换配色。
  // 好处：不依赖图片加载（file:// 也没有 canvas 污染问题），小程序直接可用。
  // ATLAS_H 必须 ≥ 8 + ceil(names/COLS)*CELL + 8（30 张 / 10 列 = 3 行 → 304px），
  // 且必须是 2 的幂（WebGL1 开 mipmap 要求 POT）→ 用 512。之前 256 只装得下 2 行多，
  // 第 2 行（off2/police/clinic/edu1-3/waste 等 10 张立面）被裁掉且 v 算出负值。
  var ATLAS_W = 1024, ATLAS_H = 512, CELL = 96, COLS = 10;
  // 服务建筑配色加浓（2026-11-23 用户反馈「服务房型跟其他的太像」）：通用立面只有
  // 墙色+窗带，远看全是同一栋白盒子。除配色外，drawFacade 里还有每类服务的专属装饰
  // （FACADE_DECOR），pushBuilding 里还有屋顶构筑物（ROOF_EXTRA），三层叠加保证一眼可辨。
  var FACADE_STYLE = {
    res: { wall: '#F3F2EE', glass: '#42594E', trim: '#5BBE8A' },
    com: { wall: '#E9F0F6', glass: '#2F4B64', trim: '#4E9BD1' },
    ind: { wall: '#F0EAE1', glass: '#4A4137', trim: '#D98A45' },
    off: { wall: '#F0ECF7', glass: '#3A3357', trim: '#8B79C9' },
    utility: { wall: '#CFE9EF', glass: '#1E545F', trim: '#2FB0C4' },
    police: { wall: '#D6DFF3', glass: '#1D3060', trim: '#3E62B4' },
    fire: { wall: '#F2DFD8', glass: '#54241E', trim: '#C0392E' },
    clinic: { wall: '#EDF7F3', glass: '#25554A', trim: '#3FA98C' },
    edu1: { wall: '#F7EFDA', glass: '#4E4022', trim: '#D8974E' },
    edu2: { wall: '#F2E4C6', glass: '#4C3A1C', trim: '#B4703F' },
    edu3: { wall: '#E7EAEE', glass: '#36414C', trim: '#4A545E' },
    waste: { wall: '#A9A87E', glass: '#4C4C30', trim: '#6E7058' },
    cemetery: { wall: '#E6EDE7', glass: '#3F4A44', trim: '#9FB0A0' },
    park: { wall: '#DFF3E4', glass: '#2F5B3C', trim: '#5FC47A' },
    parking: { wall: '#E3E7EE', glass: '#33404F', trim: '#4B586B' },
  };

  function hexDark(hex, f) {
    var n = parseInt(hex.slice(1), 16);
    var r = Math.round(((n >> 16) & 255) * f), g = Math.round(((n >> 8) & 255) * f), b = Math.round((n & 255) * f);
    return 'rgb(' + r + ',' + g + ',' + b + ')';
  }

  // 服务建筑立面专属装饰（画在通用窗带之上、转角阴影之前）。
  // 符号选大色块/高对比形状，游戏相机距离（每格 20~60px）下依然可辨。
  function decorStripes(c2, x, y, y0, y1) {
    // 黄黑斜纹警示带（水电/工程识别纹）
    var n = 12;
    for (var i = -n; i < CELL + n; i += n) {
      c2.fillStyle = ((i / n) | 0) % 2 === 0 ? '#E8C840' : '#3A3830';
      c2.beginPath();
      c2.moveTo(x + i, y + y1);
      c2.lineTo(x + i + n * 0.62, y + y0);
      c2.lineTo(x + i + n * 1.62, y + y0);
      c2.lineTo(x + i + n, y + y1);
      c2.closePath();
      c2.fill();
    }
  }
  function decorWindows(c2, x, y, st, cols, rows) {
    // 学校：盖掉通用横窗带，改成整齐方格窗 + accent 腰线
    c2.fillStyle = st.wall;
    c2.fillRect(x + 4, y + 10, CELL - 8, 66);
    var gw = CELL - 24, gh = 44;
    for (var r = 0; r < rows; r++) {
      for (var k = 0; k < cols; k++) {
        var wx = x + 14 + k * (gw / cols) + 2, wy = y + 20 + r * (gh / rows) + 2;
        c2.fillStyle = st.glass;
        c2.fillRect(wx, wy, 11, 9);
        c2.fillStyle = 'rgba(255,255,255,0.30)';
        c2.fillRect(wx, wy, 11, 2);
      }
    }
    c2.fillStyle = st.trim;
    c2.fillRect(x + 4, y + 66, CELL - 8, 4);
  }
  var FACADE_DECOR = {
    utility: function (c2, x, y, st) {
      // 水电：左侧立管 + 大圆罐端盖 + 底部警示斜纹
      c2.fillStyle = st.trim;
      c2.fillRect(x + 12, y + 16, 7, 46);
      c2.fillStyle = 'rgba(255,255,255,0.35)';
      c2.fillRect(x + 13, y + 16, 2, 46);
      c2.fillStyle = hexDark(st.trim, 0.7);
      c2.beginPath(); c2.arc(x + 56, y + 34, 11, 0, Math.PI * 2); c2.fill();
      c2.fillStyle = st.trim;
      c2.beginPath(); c2.arc(x + 56, y + 34, 8, 0, Math.PI * 2); c2.fill();
      decorStripes(c2, x, y, 62, 74);
    },
    police: function (c2, x, y, st) {
      // 警察：深蓝腰线 + 白色圆徽 + 两端蓝白方格纹
      c2.fillStyle = st.trim;
      c2.fillRect(x + 4, y + 40, CELL - 8, 14);
      c2.fillStyle = '#FFFFFF';
      c2.beginPath(); c2.arc(x + 48, y + 47, 7, 0, Math.PI * 2); c2.fill();
      c2.fillStyle = st.trim;
      c2.beginPath(); c2.arc(x + 48, y + 47, 4, 0, Math.PI * 2); c2.fill();
      for (var i = 0; i < 4; i++) {
        c2.fillStyle = i % 2 ? '#FFFFFF' : st.trim;
        c2.fillRect(x + 8 + i * 5, y + 43, 5, 8);
        c2.fillStyle = i % 2 ? st.trim : '#FFFFFF';
        c2.fillRect(x + 62 + i * 5, y + 43, 5, 8);
      }
    },
    fire: function (c2, x, y, st) {
      // 消防：红色横带 + 两扇大红卷帘车库门
      c2.fillStyle = st.trim;
      c2.fillRect(x + 4, y + 16, CELL - 8, 6);
      var doors = [10, 52];
      for (var d = 0; d < 2; d++) {
        var dx = x + doors[d];
        c2.fillStyle = st.trim;
        c2.fillRect(dx, y + 42, 34, 32);
        c2.fillStyle = hexDark(st.trim, 0.75);
        for (var s = 0; s < 4; s++) c2.fillRect(dx + 2, y + 45 + s * 7, 30, 3);
        c2.fillStyle = 'rgba(255,255,255,0.28)';
        c2.fillRect(dx, y + 42, 2, 32);
      }
    },
    clinic: function (c2, x, y) {
      // 诊所：白墙正中红十字（深色衬边保远距对比）
      c2.fillStyle = 'rgba(0,0,0,0.18)';
      c2.fillRect(x + 44, y + 26, 10, 26);
      c2.fillRect(x + 36, y + 34, 26, 10);
      c2.fillStyle = '#D94036';
      c2.fillRect(x + 45, y + 27, 8, 24);
      c2.fillRect(x + 37, y + 35, 24, 8);
    },
    edu1: function (c2, x, y, st) { decorWindows(c2, x, y, st, 4, 2); },
    edu2: function (c2, x, y, st) { decorWindows(c2, x, y, st, 4, 3); },
    edu3: function (c2, x, y, st) {
      // 大学：整面玻璃幕墙 + 密集竖框 + 分层横梁 + 底层大堂
      c2.fillStyle = st.glass;
      c2.fillRect(x + 4, y + 10, CELL - 8, 66);
      c2.fillStyle = 'rgba(255,255,255,0.5)';
      for (var m2 = 0; m2 < 9; m2++) c2.fillRect(x + 8 + m2 * 10, y + 10, 2, 66);
      c2.fillStyle = 'rgba(0,0,0,0.28)';
      for (var f2 = 0; f2 < 5; f2++) c2.fillRect(x + 4, y + 16 + f2 * 12, CELL - 8, 2);
      c2.fillStyle = st.trim;
      c2.fillRect(x + 4, y + 8, CELL - 8, 3);
      c2.fillStyle = 'rgba(255,255,255,0.22)';
      c2.fillRect(x + 8, y + 62, CELL - 16, 12);
    },
    waste: function (c2, x, y, st) {
      // 垃圾场：波纹板竖条纹墙 + 锈斑 + 深色顶棚带
      for (var w2 = 0; w2 < 12; w2++) {
        c2.fillStyle = w2 % 2 ? 'rgba(0,0,0,0.12)' : 'rgba(255,255,255,0.10)';
        c2.fillRect(x + 6 + w2 * 7.2, y + 12, 3.6, 66);
      }
      c2.fillStyle = 'rgba(122,64,40,0.55)';
      c2.fillRect(x + 20, y + 26, 9, 6);
      c2.fillRect(x + 58, y + 40, 7, 5);
      c2.fillRect(x + 36, y + 52, 10, 7);
      c2.fillStyle = st.trim;
      c2.fillRect(x + 4, y + 8, CELL - 8, 5);
    },
  };

  // 画一张正交立面：底层基座 + 多层玻璃窗带 + 女儿墙 + 服务专属装饰 + 两侧转角阴影
  function drawFacade(c2, x, y, name, level) {
    // 全名优先（edu1/edu2/edu3 各有配色），无则去掉数字后缀再查，最后兜底住宅白墙
    var st = FACADE_STYLE[name] || FACADE_STYLE[name.replace(/\d+$/, '')] || FACADE_STYLE.res;
    var rows = 3 + Math.max(1, Math.min(3, level));   // 密度越高楼层越多
    c2.fillStyle = st.wall;
    c2.fillRect(x, y, CELL, CELL);
    // 女儿墙（顶部深色压边）
    c2.fillStyle = hexDark(st.trim, 0.62);
    c2.fillRect(x, y, CELL, 8);
    // 底层基座
    c2.fillStyle = hexDark(st.wall, 0.84);
    c2.fillRect(x, y + CELL - 14, CELL, 14);
    c2.fillStyle = hexDark(st.trim, 1.0);
    c2.fillRect(x, y + CELL - 14, CELL, 3);
    // 玻璃窗带（横向长窗 + 竖框），留出左右各 9px 墙垛
    var top = 16, bottom = CELL - 22, avail = bottom - top, rowH = avail / rows;
    for (var r = 0; r < rows; r++) {
      var by = y + top + r * rowH;
      var wh = Math.max(3, rowH * 0.58);
      c2.fillStyle = st.glass;
      c2.fillRect(x + 9, by, CELL - 18, wh);
      c2.fillStyle = 'rgba(255,255,255,0.32)';
      for (var m = 1; m < 4; m++) {
        c2.fillRect(x + 9 + m * (CELL - 18) / 4 - 1, by, 2, wh);
      }
      c2.fillStyle = 'rgba(255,255,255,0.16)';
      c2.fillRect(x + 9, by, CELL - 18, 1);
    }
    // 服务建筑专属装饰（在通用窗带之上叠加符号/结构）
    if (FACADE_DECOR[name]) FACADE_DECOR[name](c2, x, y, st);
    // 转角阴影：两侧压暗，盒体看起来有厚度（最后画，统一压住装饰边缘）
    c2.fillStyle = 'rgba(0,0,0,0.10)';
    c2.fillRect(x, y, 5, CELL);
    c2.fillStyle = 'rgba(0,0,0,0.16)';
    c2.fillRect(x + CELL - 5, y, 5, CELL);
  }

  function buildAtlas(names) {
    var cv = opts.makeCanvas(ATLAS_W, ATLAS_H);   // 页面层提供离屏 2d canvas
    var c2 = cv.getContext('2d');
    c2.clearRect(0, 0, ATLAS_W, ATLAS_H);
    // 整张 atlas 先填白：顶点色几何（地面/白线/天空/车）统一采样 whiteUV，
    // 若采样到透明像素，rgb 会是 0（不混合时直接画黑），所以底色必须是白。
    c2.fillStyle = '#FFFFFF';
    c2.fillRect(0, 0, ATLAS_W, ATLAS_H);
    whiteUV = [0, 0, 1 / ATLAS_W, 1 / ATLAS_H];
    atlasRects = {};
    (names || []).forEach(function (name, i) {
      if (name === 'ground' || name === 'shadow' || name.indexOf('road') === 0) return;  // 平地类不建立面
      var col = i % COLS, row = Math.floor(i / COLS);
      var x = 8 + col * CELL, y = 8 + row * CELL;
      var m = /^([a-z]+)(\d+)?$/.exec(name);
      var level = m && m[2] ? Number(m[2]) : 1;
      drawFacade(c2, x, y, name, level);
      // flipY 上传后 GPU v = 1 - 图像py/H；v0=贴图底边、v1=贴图顶边
      atlasRects[name] = [x / ATLAS_W, 1 - (y + CELL) / ATLAS_H,
        (x + CELL) / ATLAS_W, 1 - y / ATLAS_H];
    });
    whiteUV = [0, 0, 1 / ATLAS_W, 1 / ATLAS_H];
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, cv);
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.generateMipmap(gl.TEXTURE_2D);
    return atlasRects;
  }

  // ---- 几何生成 ------------------------------------------------------
  // 顶点：x,y,z, u,v, r,g,b（stride 32B）
  function cellWorld(x, y) { return [x - HALF, y - HALF]; }

  // 天空：NDC 全屏 quad（uVP 用单位矩阵绘制），上下顶点不同色形成渐变。
  // 数据固定不变，只需建一次；UV 固定取 atlas 白块左上角。
  function buildSky() {
    var arr = [], u0 = 0.0004, v0 = 0.0004;
    function vert(x, y, c) { arr.push(x, y, 0, u0, v0, c[0], c[1], c[2]); }
    vert(-1, -1, SKY_BOTTOM); vert(1, -1, SKY_BOTTOM); vert(1, 1, SKY_TOP);
    vert(-1, -1, SKY_BOTTOM); vert(1, 1, SKY_TOP); vert(-1, 1, SKY_TOP);
    skyCount = arr.length / 8;
    if (!skyVB) skyVB = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, skyVB);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(arr), gl.STATIC_DRAW);
  }

  function pushQuad(arr, p0, p1, p2, p3, uv, col) {
    // p*: [x,y,z]（p0=左下 p1=右下 p2=右上 p3=左上 的 UV 语义）；uv: [u0,v0,u1,v1]
    // v0=贴图底部 / v1=贴图顶部（UNPACK_FLIP_Y 后 v=0 是图像底边）。两三角共 6 顶点。
    var uvs = [[uv[0], uv[1]], [uv[2], uv[1]], [uv[2], uv[3]], [uv[0], uv[3]]];
    var ps = [p0, p1, p2, p3];
    [[0, 1, 2], [0, 2, 3]].forEach(function (tri) {
      tri.forEach(function (k) {
        arr.push(ps[k][0], ps[k][1], ps[k][2], uvs[k][0], uvs[k][1], col[0], col[1], col[2]);
      });
    });
  }

  function pushTri(arr, p0, p1, p2, col) {
    // 三角面（山墙用）：纯顶点色，whiteUV 采样
    [p0, p1, p2].forEach(function (p) {
      arr.push(p[0], p[1], p[2], whiteUV[0], whiteUV[1], col[0], col[1], col[2]);
    });
  }

  function wallColor(cell) {
    // 未连通的建筑压暗（整格草地上其实不画建筑压暗，改为建筑整体变暗）
    return cell.connected ? 1 : 0.45;
  }

  // 屋顶小构筑物（服务建筑）：4 侧面 + 顶面，顶点色随朝向明暗。让每类服务在天际线
  // 上有独特剪影（水塔/蓝牌/警灯塔/红十字/钟塔/集装箱），呼应 2D 贴图的屋顶元素。
  function pushRoofBox(arr, cx, cz, y0, y1, hx, hz, col) {
    var corners = [[cx - hx, cz + hz], [cx + hx, cz + hz], [cx + hx, cz - hz], [cx - hx, cz - hz]];
    var sides = [[0, 1], [1, 2], [2, 3], [3, 0]];
    var shade = [1.0, 0.84, 0.76, 0.9];
    for (var si = 0; si < 4; si++) {
      var a = corners[sides[si][0]], b = corners[sides[si][1]];
      pushQuad(arr,
        [a[0], y0, a[1]], [b[0], y0, b[1]], [b[0], y1, b[1]], [a[0], y1, a[1]],
        whiteUV, [col[0] * shade[si], col[1] * shade[si], col[2] * shade[si]]);
    }
    pushQuad(arr,
      [cx - hx, y1 + 0.004, cz + hz], [cx + hx, y1 + 0.004, cz + hz],
      [cx + hx, y1 + 0.004, cz - hz], [cx - hx, y1 + 0.004, cz - hz],
      whiteUV, [Math.min(1, col[0] * 1.08), Math.min(1, col[1] * 1.08), Math.min(1, col[2] * 1.08)]);
  }
  function pushRooftop(arr, cell, cx, cz, y1, dim) {
    var c = function (r, g, b) { return [r * dim, g * dim, b * dim]; };
    switch (cell.type) {
      case 'utility':   // 水塔：青罐体 + 深色锥顶座
        pushRoofBox(arr, cx - 0.06, cz, y1, y1 + 0.34, 0.17, 0.17, c(0.31, 0.66, 0.72));
        pushRoofBox(arr, cx - 0.06, cz, y1 + 0.34, y1 + 0.44, 0.11, 0.11, c(0.20, 0.45, 0.50));
        break;
      case 'police':    // 蓝色标识牌（扁盒）
        pushRoofBox(arr, cx, cz, y1, y1 + 0.16, 0.24, 0.10, c(0.24, 0.31, 0.55));
        break;
      case 'fire':      // 警灯塔：灰塔身 + 红色警灯顶
        pushRoofBox(arr, cx + 0.10, cz, y1, y1 + 0.30, 0.11, 0.11, c(0.58, 0.56, 0.54));
        pushRoofBox(arr, cx + 0.10, cz, y1 + 0.30, y1 + 0.40, 0.13, 0.13, c(0.72, 0.28, 0.24));
        break;
      case 'clinic':    // 屋顶红十字：两根红杆十字交叉（俯视即红十字）
        pushRoofBox(arr, cx, cz, y1, y1 + 0.10, 0.22, 0.06, c(0.78, 0.22, 0.20));
        pushRoofBox(arr, cx, cz, y1, y1 + 0.115, 0.06, 0.22, c(0.78, 0.22, 0.20));
        break;
      case 'edu1':      // 小学：屋顶设备间
      case 'edu2':      // 中学：屋顶设备间
        pushRoofBox(arr, cx - 0.08, cz + 0.06, y1, y1 + 0.10, 0.12, 0.10, c(0.52, 0.44, 0.32));
        break;
      case 'edu3':      // 大学钟塔：浅色塔身 + 深色塔帽（呼应 2D 钟塔）
        pushRoofBox(arr, cx, cz, y1, y1 + 0.42, 0.13, 0.13, c(0.82, 0.83, 0.85));
        pushRoofBox(arr, cx, cz, y1 + 0.42, y1 + 0.52, 0.17, 0.17, c(0.22, 0.25, 0.28));
        break;
      case 'waste':     // 锈蚀集装箱两只
        pushRoofBox(arr, cx - 0.14, cz + 0.10, y1, y1 + 0.14, 0.12, 0.08, c(0.55, 0.30, 0.22));
        pushRoofBox(arr, cx + 0.14, cz - 0.08, y1, y1 + 0.12, 0.10, 0.07, c(0.42, 0.46, 0.50));
        break;
    }
  }

  // 建筑盒子：底面方形 footprint、四立面贴 atlas、顶面顶点色
  function pushBuilding(arr, x, y, cell) {
    var w0 = cellWorld(x, y);
    var h = buildingHeight(cell.type, cell.level) / 96 * HEIGHT_SCALE;   // 格
    var half = FOOT_RATIO / 2;
    var cx = w0[0], cz = w0[1];
    var name = cell.type + (cell.level && !(SERVICE_HEIGHT[cell.type] !== undefined) ? cell.level : '');
    if (cell.type === 'res' || cell.type === 'com' || cell.type === 'ind' || cell.type === 'off') name = cell.type + cell.level;
    var rect = (atlasRects && atlasRects[name]) || whiteUV;
    var dim = wallColor(cell);
    var topCol = (TOP_COLORS[cell.type] || TOP_COLORS.def).map(function (v) { return v * dim; });
    // 四个侧面（逆时针从上看）：南(+z)、东(+x)、北(-z)、西(-x)；只画朝外的两面即可，
    // 但背面剔除需要正确绕向 —— 统一画 4 面，绕向按外法线排。
    var y0 = 0.01, y1 = h;
    var corners = [
      [cx - half, cz + half], [cx + half, cz + half],   // 南西、南东
      [cx + half, cz - half], [cx - half, cz - half],   // 北东、北西
    ];
    var sides = [
      [corners[0], corners[1]],   // 南面：外法线 +z
      [corners[1], corners[2]],   // 东面：+x
      [corners[2], corners[3]],   // 北面：-z
      [corners[3], corners[0]],   // 西面：-x
    ];
    var SIDE_SHADE = [1.0, 0.84, 0.76, 0.9];   // 固定光向：南亮、北暗，出体块感
    sides.forEach(function (sd, si) {
      var a = sd[0], b = sd[1];
      var s = dim * SIDE_SHADE[si];
      // 逆时针（从外部看）：底 a → 底 b → 顶 b → 顶 a
      pushQuad(arr,
        [a[0], y0, a[1]], [b[0], y0, b[1]], [b[0], y1, b[1]], [a[0], y1, a[1]],
        rect, [s, s, s]);
    });
    // 顶面（法线 +y 的绕向）：SW → SE → NE → NW。
    // 低密度住宅（2026-11-23 用户要求）：平顶换成三角坡屋顶（屋脊沿 x 轴，南北两坡 + 东西山墙），
    // 屋顶高度 = 体高的一半 —— 城市天际线的低密度住宅小屋轮廓。商业/工业/办公低密度仍是平顶。
    if (cell.type === 'res' && Math.round(cell.level) === 1) {
      var roofH = h * 0.5, y2 = y1 + roofH;
      // 南坡（朝光，亮）：屋檐南边 → 屋脊
      pushQuad(arr,
        [cx - half, y1, cz + half], [cx + half, y1, cz + half],
        [cx + half, y2, cz], [cx - half, y2, cz],
        whiteUV, topCol);
      // 北坡（背光，暗）
      pushQuad(arr,
        [cx + half, y1, cz - half], [cx - half, y1, cz - half],
        [cx - half, y2, cz], [cx + half, y2, cz],
        whiteUV, [topCol[0] * 0.76, topCol[1] * 0.76, topCol[2] * 0.76]);
      // 东西山墙三角（墙面色 + 对应侧面明暗）
      pushTri(arr,
        [cx + half, y1, cz + half], [cx + half, y1, cz - half], [cx + half, y2, cz],
        [dim * 0.84, dim * 0.84, dim * 0.84]);
      pushTri(arr,
        [cx - half, y1, cz - half], [cx - half, y1, cz + half], [cx - half, y2, cz],
        [dim * 0.9, dim * 0.9, dim * 0.9]);
    } else {
      pushQuad(arr,
        [cx - half, y1 + 0.005, cz + half], [cx + half, y1 + 0.005, cz + half],
        [cx + half, y1 + 0.005, cz - half], [cx - half, y1 + 0.005, cz - half],
        whiteUV, topCol);
      // 服务建筑屋顶构筑物（水塔/蓝牌/警灯塔/红十字/钟塔/集装箱）
      if (ROOF_EXTRA[cell.type] !== undefined) pushRooftop(arr, cell, cx, cz, y1, dim);
    }
  }

  // 地面 + 道路白线 + 高速虚线（顶点色）
  function rebuildGround(city) {
    if (groundVB) gl.deleteBuffer(groundVB);   // 重建前释放旧 VBO，避免每次建造泄漏显存
    groundVB = null;
    var arr = [];
    // 地形底座：比地图大一圈的「立体沙盘」，低角度视角下城市不会浮在虚空里。
    // 顶面略低于格子面（-0.02）避免 z-fighting；四周是地层剖面（土色）。
    (function slab() {
      // 顶面压到 -0.12：与地块（y=0）拉开距离，否则远处深度精度不足会出现一片
      // 横贯地图的 z-fighting 条纹。底座本身在整体视角下几乎看不到顶面。
      var M = HALF + 1.4, D = 1.6, t = -0.12, soil = GROUND_COLORS.soil;
      pushQuad(arr, [-M, t, -M], [-M, t, M], [M, t, M], [M, t, -M], whiteUV, GROUND_COLORS.slab);
      var shades = [1.0, 0.86, 0.78, 0.9];   // 南/东/北/西 面亮度差，出立体感
      pushQuad(arr, [-M, -D, M], [M, -D, M], [M, 0, M], [-M, 0, M], whiteUV,
        [soil[0] * shades[0], soil[1] * shades[0], soil[2] * shades[0]]);
      pushQuad(arr, [M, -D, M], [M, -D, -M], [M, 0, -M], [M, 0, M], whiteUV,
        [soil[0] * shades[1], soil[1] * shades[1], soil[2] * shades[1]]);
      pushQuad(arr, [M, -D, -M], [-M, -D, -M], [-M, 0, -M], [M, 0, -M], whiteUV,
        [soil[0] * shades[2], soil[1] * shades[2], soil[2] * shades[2]]);
      pushQuad(arr, [-M, -D, -M], [-M, -D, M], [-M, 0, M], [-M, 0, -M], whiteUV,
        [soil[0] * shades[3], soil[1] * shades[3], soil[2] * shades[3]]);
    })();
    for (var y = 0; y < size; y++) {
      for (var x = 0; x < size; x++) {
        var cell = city.cells[y * size + x];
        var w0 = cellWorld(x, y);
        var x0 = w0[0] - 0.5, x1 = w0[0] + 0.5, z0 = w0[1] - 0.5, z1 = w0[1] + 0.5;
        var col;
        if (isHighwayEdge(x, y)) col = GROUND_COLORS.highway;
        else if (cell.type === 'road') col = GROUND_COLORS.road;
        else if (cell.type === 'park') col = GROUND_COLORS.park;
        else if (cell.type === 'parking') col = GROUND_COLORS.parking;
        else if (cell.type === 'cemetery') col = GROUND_COLORS.cemetery;
        else col = GROUND_COLORS.grass;
        if (cell.type !== 'empty' && cell.type !== 'road' && cell.connected === false &&
            cell.type !== 'park' && cell.type !== 'parking' && cell.type !== 'cemetery') {
          // 未连通建筑所在格：草色微暗即可（建筑本体已压暗）
          col = [col[0] * 0.82, col[1] * 0.82, col[2] * 0.82];
        }
        // 草地不加贴图，靠「每格哈希亮度」做出草皮层次：
        // 贴图噪点在缩小时和 mipmap 打架会出摩尔纹，放大时网格重复，得不偿失。
        if (cell.type === 'empty' && !isHighwayEdge(x, y)) {
          var hsh = ((x * 73856093) ^ (y * 19349663)) & 255;
          var k2 = 0.93 + (hsh % 9) * 0.018;
          col = [col[0] * k2, col[1] * k2, col[2] * k2];
        }
        pushQuad(arr, [x0, 0, z0], [x0, 0, z1], [x1, 0, z1], [x1, 0, z0], whiteUV, col);
      }
    }
    // 道路白线：世界四邻（3D 不存在视角旋转，直接世界邻居）
    for (var y2 = 0; y2 < size; y2++) {
      for (var x2 = 0; x2 < size; x2++) {
        var c2 = city.cells[y2 * size + x2];
        var p = cellWorld(x2, y2);
        var lw = 0.055, ly = 0.012;
        function line(dx0, dz0, dx1, dz1) {
          var a = [p[0] + dx0, ly, p[1] + dz0], b = [p[0] + dx1, ly, p[1] + dz1];
          // 垂直于线方向：线沿 z 时 perp 在 x，线沿 x 时 perp 在 z
          var perp = (dx0 === dx1) ? [0.055, 0, 0] : [0, 0, 0.055];
          // 顺序必须是 矩形四角（A+perp → B+perp → B-perp → A-perp），
          // 否则会画成自交的「蝴蝶结」四边形（白线整段消失）
          pushQuad(arr,
            [a[0] + perp[0], ly, a[2] + perp[2]], [b[0] + perp[0], ly, b[2] + perp[2]],
            [b[0] - perp[0], ly, b[2] - perp[2]], [a[0] - perp[0], ly, a[2] - perp[2]],
            whiteUV, GROUND_COLORS.line);
        }
        if (c2.type === 'road') {
          if (isRoadCell(city, x2, y2 - 1)) line(0, 0, 0, -0.5);
          if (isRoadCell(city, x2, y2 + 1)) line(0, 0, 0, 0.5);
          if (isRoadCell(city, x2 - 1, y2)) line(0, 0, -0.5, 0);
          if (isRoadCell(city, x2 + 1, y2)) line(0, 0, 0.5, 0);
        }
        if (y2 === HIGHWAY_Y || y2 === size - 1) {
          // 横向高速虚线：两条平行线，每格一段
          for (var k = 0; k < 2; k++) {
            var zz = -0.22 + k * 0.44;
            pushQuad(arr, [p[0] - 0.3, ly, p[1] + zz - 0.03], [p[0] + 0.3, ly, p[1] + zz - 0.03],
              [p[0] + 0.3, ly, p[1] + zz + 0.03], [p[0] - 0.3, ly, p[1] + zz + 0.03],
              whiteUV, GROUND_COLORS.line);
          }
        }
        if (x2 === HIGHWAY_Y || x2 === size - 1) {
          // 纵向高速虚线（左右两条高速列）：两条平行线沿 z 方向，每格一段
          for (var kv = 0; kv < 2; kv++) {
            var xx = -0.22 + kv * 0.44;
            pushQuad(arr, [p[0] + xx - 0.03, ly, p[1] - 0.3], [p[0] + xx - 0.03, ly, p[1] + 0.3],
              [p[0] + xx + 0.03, ly, p[1] + 0.3], [p[0] + xx + 0.03, ly, p[1] - 0.3],
              whiteUV, GROUND_COLORS.line);
          }
        }
      }
    }
    uploadStatic('ground', arr, function (vb, data, n) { groundVB = vb; groundCount = n; });
  }

  function isRoadCell(city, x, y) {
    if (x < 0 || y < 0 || x >= size || y >= size) return false;
    if (isHighwayEdge(x, y)) return true;
    return city.cells[y * size + x].type === 'road';
  }

  function rebuildBuildings(city) {
    if (buildVB) gl.deleteBuffer(buildVB);
    buildVB = null;
    if (shadowVB) gl.deleteBuffer(shadowVB);
    shadowVB = null;
    var arr = [], sh = [];
    for (var i = 0; i < city.cells.length; i++) {
      var cell = city.cells[i];
      if (cell.type !== 'empty' && (cell.type !== 'road' && cell.type !== 'park' &&
        cell.type !== 'parking' && cell.type !== 'cemetery')) {
        var cx = i % size, cy = Math.floor(i / size);
        pushBuilding(arr, cx, cy, cell);
        // 方向投影：光从南偏西来，影子朝北偏东倒，长度随楼高
        pushShadow(sh, cx, cy, buildingHeight(cell.type, cell.level) / 96 * HEIGHT_SCALE);
      }
    }
    uploadStatic('build', arr, function (vb, data, n) { buildVB = vb; buildCount = n; });
    uploadStatic('shadow', sh, function (vb, data, n) { shadowVB = vb; shadowCount = n; });
  }

  // 楼影：底面方块沿光照方向斜投到地面（y=0.005 略高于地块，避免 z-fighting）
  function pushShadow(arr, x, y, h) {
    var w0 = cellWorld(x, y), half = FOOT_RATIO / 2;
    var dx = h * 0.30, dz = -h * 0.38;         // 影子偏移方向（+x/-z）
    var e = 0.04;
    // 绕向与地块一致（+y 法线）：西北 → 西南 → 东南 → 东北
    var a = [w0[0] - half - e + dx, 0.005, w0[1] - half - e + dz];
    var b = [w0[0] - half - e + dx, 0.005, w0[1] + half + e + dz];
    var c = [w0[0] + half + e + dx, 0.005, w0[1] + half + e + dz];
    var d = [w0[0] + half + e + dx, 0.005, w0[1] - half - e + dz];
    pushQuad(arr, a, b, c, d, whiteUV, [0.06, 0.10, 0.08]);
  }

  function uploadStatic(kind, arr, setter) {
    var data = new Float32Array(arr);
    var vb = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, vb);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
    setter(vb, data, data.length / 8);
  }

  // rebuild 全量：城市数据变化（建造 / 拆除 / 载入 demo）后调用。
  // 连续拖动铺路时一帧可能改多格：页面用 markDirty() 标脏，drawFrame 每帧最多重建一次。
  var dirty = false;
  function markDirty() { dirty = true; }
  // 每格建筑体高（格单位，0=无建筑）。供 pick 做「射线-楼体」遮挡判定：
  // 鼠标压在楼身上时射线先穿楼再落地，只按 y=0 求交会把高亮甩到楼后一格（2026-11-23 用户反馈）。
  var cellH = null;
  function rebuildHeights(city) {
    var n = city.cells.length;
    if (!cellH || cellH.length !== n) cellH = new Float32Array(n);
    for (var i = 0; i < n; i++) {
      var c = city.cells[i];
      cellH[i] = (c.type === 'empty' || c.type === 'road' || c.type === 'park' ||
        c.type === 'parking' || c.type === 'cemetery')
        ? 0
        : buildingHeight(c.type, c.level) / 96 * HEIGHT_SCALE + (ROOF_EXTRA[c.type] || 0);
    }
  }
  function rebuild(city) {
    rebuildGround(city);
    rebuildBuildings(city);
    rebuildHeights(city);
    dirty = false;
  }

  // ---- 相机与拾取 ----------------------------------------------------
  function eyePos() {
    var cp = Math.cos(cam.pitch), sp = Math.sin(cam.pitch);
    return [cam.tx + cam.dist * cp * Math.sin(cam.yaw), cam.dist * sp, cam.tz + cam.dist * cp * Math.cos(cam.yaw)];
  }
  function viewProj(width, height) {
    var eye = eyePos();
    var view = m4LookAt(eye, [cam.tx, 0, cam.tz], [0, 1, 0]);
    var proj = m4Perspective(FOV, width / Math.max(1, height), 1.0, 320);   // 近裁面别太小：深度精度决定是否 z-fighting
    return m4Multiply(proj, view);
  }
  function clampTarget() {
    var lim = HALF + 6;
    cam.tx = Math.max(-lim, Math.min(lim, cam.tx));
    cam.tz = Math.max(-lim, Math.min(lim, cam.tz));
  }
  function orbit(dyaw, dpitch) {
    cam.yaw += dyaw;
    cam.pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, cam.pitch + dpitch));
  }
  function zoomBy(f) {
    cam.dist = Math.max(DIST_MIN, Math.min(DIST_MAX, cam.dist * f));
  }
  // 屏幕像素位移 → 世界平移（沿相机右/前方向，保持手感与地图应用一致）
  function panByPixels(dx, dy, width, height) {
    var scale = cam.dist / Math.max(1, height) * 1.6;
    var sy = Math.sin(cam.yaw), cy = Math.cos(cam.yaw);
    // 相机右方向 = (cos yaw, 0, -sin yaw)；前方向（水平投影）= (-sin yaw, 0, -cos yaw)
    cam.tx += (-dx * cy - dy * sy) * scale;
    cam.tz += (dx * sy - dy * cy) * scale;
    clampTarget();
  }
  function setTarget(cellX, cellY) {
    var w = cellWorld(cellX, cellY);
    cam.tx = w[0]; cam.tz = w[1];
    clampTarget();
  }
  function rotateStep(d) { orbit(d * Math.PI / 2, 0); }

  // 屏幕坐标（CSS 像素）→ 世界格；返回 {x, y} 或 null
  function pick(sx, sy, width, height) {
    var vp = viewProj(width, height);
    var inv = m4Invert(vp);
    if (!inv) return null;
    var ndcX = sx / width * 2 - 1, ndcY = 1 - sy / height * 2;
    function unproject(z) {
      var x = ndcX, y = ndcY;
      var w = inv[3] * x + inv[7] * y + inv[11] * z + inv[15];
      return [
        (inv[0] * x + inv[4] * y + inv[8] * z + inv[12]) / w,
        (inv[1] * x + inv[5] * y + inv[9] * z + inv[13]) / w,
        (inv[2] * x + inv[6] * y + inv[10] * z + inv[14]) / w,
      ];
    }
    var near = unproject(-1), far = unproject(1);
    var dir = [far[0] - near[0], far[1] - near[1], far[2] - near[2]];
    if (Math.abs(dir[1]) < 1e-6) return null;
    var t = -near[1] / dir[1];
    // 建筑体遮挡（2026-11-23 用户反馈「鼠标瞄准跟格子不对应」）：
    // 旧实现只把射线和 y=0 平面求交，鼠标压在楼身/楼顶上时射线穿过楼体落到楼后一格，
    // 高亮与鼠标错位。这里沿射线从近裁面步进（0.16 格），命中第一个建筑盒就直接返回该格；
    // 没穿过任何建筑时再走原来的地面求交（保证点空地、点远处仍然准）。
    if (cellH) {
      var dlen = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]) || 1;
      var dt = 0.16 / dlen;
      var tEnd = t > 0 ? Math.min(t, 1) : 1;      // 有地面交点就只扫到交点，否则扫到远平面
      for (var tt = dt; tt <= tEnd; tt += dt) {
        var qx = near[0] + dir[0] * tt, qy = near[1] + dir[1] * tt, qz = near[2] + dir[2] * tt;
        if (qy < 0) break;                        // 已低于地面：后面交给地面求交
        var hx = Math.floor(qx + HALF + 0.5), hz = Math.floor(qz + HALF + 0.5);
        if (hx < 0 || hz < 0 || hx >= size || hz >= size) continue;
        var hi = hz * size + hx;
        var hh = cellH[hi];
        if (hh <= 0.05) continue;                 // 该格没建筑
        if (Math.abs(qx - (hx - HALF)) > FOOT_RATIO / 2 + 0.02) continue;   // 不在 building footprint 内
        if (Math.abs(qz - (hz - HALF)) > FOOT_RATIO / 2 + 0.02) continue;
        if (qy <= hh + 0.02) return { x: hx, y: hz, i: hi };
      }
    }
    if (t < 0) return null;
    var wx = near[0] + dir[0] * t, wz = near[2] + dir[2] * t;
    var gx = Math.round(wx + HALF), gy = Math.round(wz + HALF);
    if (gx < 0 || gy < 0 || gx >= size || gy >= size) return null;
    return { x: gx, y: gy, i: gy * size + gx };
  }
  // 世界格 → 屏幕像素（供面板锚定等）
  function project(x, y, width, height) {
    var vp = viewProj(width, height);
    var w = cellWorld(x, y);
    var px = vp[0] * w[0] + vp[4] * 0 + vp[8] * w[1] + vp[12];
    var py = vp[1] * w[0] + vp[5] * 0 + vp[9] * w[1] + vp[13];
    var pw = vp[3] * w[0] + vp[7] * 0 + vp[11] * w[1] + vp[15];
    return { x: (px / pw * 0.5 + 0.5) * width, y: (-py / pw * 0.5 + 0.5) * height };
  }

  // ---- 动态物体：车辆 / 行人 / 高亮 ----------------------------------
  function pushBoxY(arr, cx, cz, w, d, h, yaw, col, y0) {
    // 以 (cx,cz) 为中心、绕 Y 旋转 yaw 的盒子
    var cos = Math.cos(yaw), sin = Math.sin(yaw);
    function pt(lx, lz) { return [cx + lx * cos - lz * sin, y0, cz + lx * sin + lz * cos]; }
    var hw = w / 2, hd = d / 2;
    var p = [pt(-hw, -hd), pt(hw, -hd), pt(hw, hd), pt(-hw, hd)];
    var y1 = y0 + h;
    var sides = [[1, 0], [2, 1], [3, 2], [0, 3]];   // 绕向保证外法线朝外
    var shade = [1.0, 0.72, 0.62, 0.86];
    sides.forEach(function (sd, i) {
      var a = p[sd[0]], b = p[sd[1]];
      var c = col.map(function (v) { return v * shade[i]; });
      pushQuad(arr, [a[0], y0, a[2]], [b[0], y0, b[2]], [b[0], y1, b[2]], [a[0], y1, a[2]], whiteUV, c);
    });
    // 顶面绕向（局部 +y）：p0 → p3 → p2 → p1
    pushQuad(arr, [p[0][0], y1 + 0.004, p[0][2]], [p[3][0], y1 + 0.004, p[3][2]],
      [p[2][0], y1 + 0.004, p[2][2]], [p[1][0], y1 + 0.004, p[1][2]], whiteUV,
      col.map(function (v) { return Math.min(1, v * 1.15); }));
  }

  function carColor(car) {
    if (car.state === 'jam') return [0.94, 0.27, 0.27];
    if (car.state === 'home') return [0.48, 0.85, 0.56];
    if (car.state === 'lot') return [0.41, 0.66, 1.0];
    if (car.state === 'inbound') return [0.95, 0.85, 0.55];
    return [0.95, 0.95, 0.97];
  }

  // 悬停高亮色（2026-11-23 建造辅助）：页面按 canPlace 结果设置 ——
  // 绿=可建 / 黄=未连通 / 红=不可建 / 橙=资金不足将透支。
  var hoverTint = [1, 1, 1];
  // 低画质（2026-11-25 设置项）：只跳过楼影等装饰性绘制，不改几何/拾取/模拟
  var lowQuality = false;
  function setQuality(low) { lowQuality = !!low; }
  function setHoverTint(rgb) {
    if (rgb && rgb.length === 3) hoverTint = [Number(rgb[0]) || 0, Number(rgb[1]) || 0, Number(rgb[2]) || 0];
    else hoverTint = [1, 1, 1];
  }

  function buildDynamic(city, traffic, peds, hoverIdx) {
    var arr = [];
    if (traffic && traffic.cars) {
      traffic.cars.forEach(function (car) {
        var pos = null, yaw = 0;
        if (car.path && car.path.length) {
          var a = car.path[Math.min(car.seg, car.path.length - 1)];
          var b = car.path[Math.min(car.seg + 1, car.path.length - 1)];
          pos = { x: a.x + (b.x - a.x) * car.t, y: a.y + (b.y - a.y) * car.t };
          yaw = Math.atan2(b.x - a.x, b.y - a.y);
        } else if (car.state === 'home' && car.home >= 0) {
          pos = { x: car.home % size, y: Math.floor(car.home / size) };
          yaw = Math.PI / 2;
        } else if (car.state === 'lot' && (car.lot >= 0 || car.targetLot >= 0)) {
          var li = car.lot >= 0 ? car.lot : car.targetLot;
          pos = { x: li % size, y: Math.floor(li / size) };
          yaw = Math.PI / 2;
        }
        if (!pos) return;
        if (pos.x < -0.5 || pos.y < -0.5 || pos.x >= size || pos.y >= size) return;
        var w = cellWorld(pos.x, pos.y);
        pushBoxY(arr, w[0], w[1], 0.30, 0.15, 0.10, yaw, carColor(car), 0.02);
      });
    }
    if (peds && peds.peds) {
      peds.peds.forEach(function (ped) {
        if (ped.x < 0 || ped.y < 0 || ped.x >= size || ped.y >= size) return;
        var w = cellWorld(ped.x, ped.y);
        pushBoxY(arr, w[0], w[1], 0.07, 0.07, 0.10, 0, [0.07, 0.09, 0.11], 0.01);
      });
    }
    if (hoverIdx >= 0 && hoverIdx < size * size) {
      var hx = hoverIdx % size, hz = Math.floor(hoverIdx / size);
      var w2 = cellWorld(hx, hz);
      var e = 0.48, hy = 0.03;
      var c = hoverTint;
      // 绕向必须与地面一致（p0=西北、p1=西南、p2=东南、p3=东北），否则被背面剔除
      var q = [[w2[0] - e, hy, w2[1] - e], [w2[0] - e, hy, w2[1] + e],
        [w2[0] + e, hy, w2[1] + e], [w2[0] + e, hy, w2[1] - e]];
      pushQuad(arr, q[0], q[1], q[2], q[3], whiteUV, c);
    }
    var data = new Float32Array(arr);
    if (!dynVB) dynVB = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, dynVB);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
    return data.length / 8;
  }

  // ---- 可视化叠加层（2026-11-23 优化）--------------------------------
  // mode: '' 关闭 | 'conn' 路网连通 | 'jam' 拥堵热力 | 'env' 污染噪音
  // 只画半透明色块贴地叠加，不改任何模拟数据；每帧重建（有值格数量有限）。
  var overlayMode = '';
  var overlayVB = null, overlayCount = 0, overlayAlpha = 0;
  var OVERLAY_ALPHA = { conn: 0.46, jam: 0.62, env: 0.52 };
  function setOverlay(mode) {
    overlayMode = (mode === 'conn' || mode === 'jam' || mode === 'env') ? mode : '';
  }
  function getOverlay() { return overlayMode; }

  function carGrid(traffic) {
    var g = {};
    if (!traffic || !traffic.cars) return g;
    traffic.cars.forEach(function (car) {
      var x = -1, y = -1;
      if (car.path && car.path.length) {
        var a = car.path[Math.min(car.seg, car.path.length - 1)];
        x = a.x; y = a.y;
      } else if (car.state === 'lot' && (car.lot >= 0 || car.targetLot >= 0)) {
        var li = car.lot >= 0 ? car.lot : car.targetLot;
        x = li % size; y = Math.floor(li / size);
      } else if (car.home >= 0) {
        x = car.home % size; y = Math.floor(car.home / size);
      }
      if (x < 0 || y < 0 || x >= size || y >= size) return;
      var k = y * size + x;
      g[k] = (g[k] || 0) + 1;
    });
    return g;
  }

  function buildOverlay(city, traffic) {
    var arr = [];
    if (!city || !city.cells) return arr;
    var cells = city.cells, i, c, e = 0.47, yy = 0.012;
    function quad(x, y, col) {
      var w = cellWorld(x, y);
      pushQuad(arr, [w[0] - e, yy, w[1] - e], [w[0] - e, yy, w[1] + e],
        [w[0] + e, yy, w[1] + e], [w[0] + e, yy, w[1] - e], whiteUV, col);
    }
    if (overlayMode === 'conn') {
      // 路网连通：道路绿=已接高速、红=断头；建筑只标未连通的（红）
      for (i = 0; i < cells.length; i++) {
        c = cells[i];
        if (c.type === 'road') {
          quad(i % size, Math.floor(i / size), c.connected ? [0.16, 0.72, 0.42] : [0.93, 0.22, 0.22]);
        }
      }
      for (i = 0; i < cells.length; i++) {
        c = cells[i];
        if (c.type === 'empty' || c.type === 'road' || c.connected) continue;
        quad(i % size, Math.floor(i / size), [0.95, 0.30, 0.24]);
      }
    } else if (overlayMode === 'jam') {
      var g = carGrid(traffic);
      Object.keys(g).forEach(function (k) {
        var n = g[k];
        var col = n >= 3 ? [0.96, 0.20, 0.16] : (n === 2 ? [0.98, 0.56, 0.12] : [0.99, 0.83, 0.24]);
        quad(k % size, Math.floor(k / size), col);
      });
    } else if (overlayMode === 'env') {
      // 污染（橙红，越浓越深）与噪音（紫），同格取较强者
      for (i = 0; i < cells.length; i++) {
        var p = (city.pollution && city.pollution[i]) || 0;
        var q = (city.noise && city.noise[i]) || 0;
        if (p < 0.06 && q < 0.06) continue;
        var x = i % size, y = Math.floor(i / size);
        if (p >= q) quad(x, y, [0.95, 0.22 + (1 - Math.min(1, p)) * 0.5, 0.10]);
        else quad(x, y, [0.70, 0.32, 0.95]);
      }
    }
    return arr;
  }

  // ---- 绘制 ----------------------------------------------------------
  function drawFrame(city, traffic, hoverIdx, peds, daylight, cssW, cssH) {
    if (dirty && city) rebuild(city);   // 标脏后统一在本帧重建，避免拖动铺路一帧重建多次
    var width = cssW || canvas.width / (opts.dpr || 1);
    var height = cssH || canvas.height / (opts.dpr || 1);
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.useProgram(prog);
    // 昼夜色调：正午白 → 深夜蓝
    var d = daylight === undefined || daylight === null ? 1 : Math.max(0, Math.min(1, daylight));
    var night = (1 - d) * 0.72;
    gl.uniform3f(loc.uTint, 1 - night * 0.55, 1 - night * 0.42, 1 - night * 0.12);
    gl.uniform1f(loc.uAlpha, 1);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.uniform1i(loc.uTex, 0);
    bindAttribs();

    function drawVB(vb, count) {
      if (!vb || !count) return;
      gl.bindBuffer(gl.ARRAY_BUFFER, vb);
      bindAttribs();
      gl.drawArrays(gl.TRIANGLES, 0, count);
    }
    // 1) 天空：全屏渐变 quad，NDC 直出（uVP=单位矩阵），不写深度
    if (!skyVB) buildSky();
    gl.disable(gl.DEPTH_TEST);
    gl.depthMask(false);
    gl.uniformMatrix4fv(loc.uVP, false, new Float32Array(m4Identity()));
    drawVB(skyVB, skyCount);
    gl.depthMask(true);
    gl.enable(gl.DEPTH_TEST);
    // 2) 场景
    gl.uniformMatrix4fv(loc.uVP, false, new Float32Array(viewProj(width, height)));
    drawVB(groundVB, groundCount);
    // 2.5) 可视化叠加层（连通 / 拥堵 / 环境热力）：贴地半透明色块，不写深度
    overlayCount = 0;
    if (overlayMode && city) {
      var ov = buildOverlay(city, traffic);
      if (ov.length) {
        if (!overlayVB) overlayVB = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, overlayVB);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(ov), gl.DYNAMIC_DRAW);
        overlayCount = ov.length / 8;
        overlayAlpha = OVERLAY_ALPHA[overlayMode] || 0.5;
      }
    }
    if (overlayCount) {
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      gl.depthMask(false);
      gl.uniform1f(loc.uAlpha, overlayAlpha);
      gl.bindBuffer(gl.ARRAY_BUFFER, overlayVB);
      bindAttribs();
      gl.drawArrays(gl.TRIANGLES, 0, overlayCount);
      gl.depthMask(true);
      gl.disable(gl.BLEND);
      gl.uniform1f(loc.uAlpha, 1);
    }
    // 楼影：半透明叠加在路面上（不写深度），再画楼体
    if (shadowCount && !lowQuality) {
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      gl.depthMask(false);
      gl.uniform1f(loc.uAlpha, 0.42);
      drawVB(shadowVB, shadowCount);
      gl.depthMask(true);
      gl.disable(gl.BLEND);
      gl.uniform1f(loc.uAlpha, 1);
    }
    drawVB(buildVB, buildCount);
    // 动态物体（车 / 人）。悬停高亮 quad 在缓冲末尾 6 个顶点，
    // 不透明通道要跳过它，只走下面 alpha 0.38 的半透明通道，否则先画成实心白块。
    var dynCount = buildDynamic(city, traffic, peds, hoverIdx);
    drawVB(dynVB, hoverIdx >= 0 ? dynCount - 6 : dynCount);
    // 高亮：最后画、半透明、不写深度
    if (hoverIdx >= 0) {
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      gl.depthMask(false);
      gl.uniform1f(loc.uAlpha, 0.56);
      gl.drawArrays(gl.TRIANGLES, dynCount - 6, 6);
      gl.depthMask(true);
      gl.disable(gl.BLEND);
      gl.uniform1f(loc.uAlpha, 1);
    }
  }

  function getCamera() { return { tx: cam.tx, tz: cam.tz, yaw: cam.yaw, pitch: cam.pitch, dist: cam.dist }; }
  function setCamera(next) {
    if (!next) return;
    if (next.tx !== undefined) cam.tx = next.tx;
    if (next.tz !== undefined) cam.tz = next.tz;
    if (next.yaw !== undefined) cam.yaw = next.yaw;
    if (next.pitch !== undefined) cam.pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, next.pitch));
    if (next.dist !== undefined) cam.dist = Math.max(DIST_MIN, Math.min(DIST_MAX, next.dist));
    clampTarget();
  }

  return {
    buildAtlas: buildAtlas,
    rebuild: rebuild,
    markDirty: markDirty,
    drawFrame: drawFrame,
    pick: pick,
    project: project,
    orbit: orbit,
    zoomBy: zoomBy,
    panByPixels: panByPixels,
    setTarget: setTarget,
    setCamera: setCamera,
    getCamera: getCamera,
    rotateStep: rotateStep,
    buildingHeight: buildingHeight,
    setHoverTint: setHoverTint,
    setQuality: setQuality,
    setOverlay: setOverlay,
    getOverlay: getOverlay,
  };
}

var API = {
  createRenderer: createRenderer,
  BUILD_HEIGHT: BUILD_HEIGHT,
  SERVICE_HEIGHT: SERVICE_HEIGHT,
  m4Multiply: m4Multiply,
  m4Invert: m4Invert,
};

// UMD：小程序 require / 预览 <script> 共用
if (typeof module === 'object' && module.exports) {
  module.exports = API;
} else {
  window.Render3D = API;
}
