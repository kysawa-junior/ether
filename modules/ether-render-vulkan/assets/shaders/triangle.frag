#version 450

// Constant colour so the smoke test can assert exact texel values after readback.
layout(location = 0) in vec2 fragPosition;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(1.0, 0.0, 0.0, 1.0);
}
