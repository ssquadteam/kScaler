#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
flat in int probeWork;

out vec4 fragColor;

void main() {
    if (probeWork >= 0) {
        float acc = gl_FragCoord.x * 0.001;
        for (int i = 0; i < probeWork; i++) {
            acc = acc * 1.000001 + 0.5;
        }
        if (acc < 0.0) {
            fragColor = vec4(acc);
            return;
        }
        discard;
    }

    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
