#version 450

// Fullscreen-friendly test triangle. Positions arrive as clip-space XY in [-1, 1].
layout(location = 0) in vec2 inPosition;
layout(location = 0) out vec2 fragPosition;

void main() {
    fragPosition = inPosition;
    gl_Position = vec4(inPosition, 0.0, 1.0);
}
