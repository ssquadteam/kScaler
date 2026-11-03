#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;
flat out int probeWork;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;
    probeWork = -1;
#ifdef IS_GUI
    ivec3 probeRgb = ivec3(round(Color.rgb * 255.0));
    if ((probeRgb.r == 241 || probeRgb.r == 242) && probeRgb.b >= 100) {
        bool narrow = 2.0 / ProjMat[0][0] < float(probeRgb.g) * 4.0;
        bool hit = probeRgb.r == 241 ? narrow : !narrow;
        probeWork = hit ? int(exp2(float(probeRgb.b) / 10.0)) : 0;
        int corner = gl_VertexID % 4;
        vec2 probeCorner = vec2(corner >= 2 ? 1.0 : 0.0, (corner == 1 || corner == 2) ? 1.0 : 0.0);
        gl_Position = vec4(-1.0 + 0.03 * probeCorner.x, 1.0 - 0.03 * probeCorner.y, 0.0, 1.0);
    }
#endif
}
