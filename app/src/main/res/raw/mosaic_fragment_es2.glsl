precision mediump float;
uniform sampler2D uTexSampler;
uniform float uRegionLeft;
uniform float uRegionBottom;
uniform float uRegionWidth;
uniform float uRegionHeight;
uniform float uBlockSize;
varying vec2 vTexSamplingCoord;

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 regionStart = vec2(uRegionLeft, uRegionBottom);
  vec2 regionSize = vec2(max(uRegionWidth, 0.0001), max(uRegionHeight, 0.0001));
  bool inside = uv.x >= regionStart.x && uv.x <= regionStart.x + regionSize.x &&
                uv.y >= regionStart.y && uv.y <= regionStart.y + regionSize.y;

  if (inside) {
    vec2 local = (uv - regionStart) / regionSize;
    float block = max(uBlockSize, 0.005);
    vec2 cell = floor(local / block);
    vec2 sampleLocal = (cell + vec2(0.5)) * block;
    sampleLocal = clamp(sampleLocal, vec2(0.0), vec2(1.0));
    uv = regionStart + sampleLocal * regionSize;
  }

  gl_FragColor = texture2D(uTexSampler, uv);
}
