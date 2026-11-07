#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
in vec2 texCoord0;
out vec4 fragColor;

// A lit display keeps its own brightness beyond the terrain fog distance.
// The render pipeline still depth-tests against blocks in front of the screen.
void main() {
    fragColor = vec4(texture(Sampler0, texCoord0).rgb * ColorModulator.rgb, 1.0);
}
