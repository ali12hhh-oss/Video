uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;

void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vTexSamplingCoord = (uTexTransformationMatrix * aFramePosition).xy * 0.5 + 0.5;
}
