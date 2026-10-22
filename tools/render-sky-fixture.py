#!/usr/bin/env python3
"""Render actual Solstice sky functions through a surfaceless GL test context.

This fixture checks sky/color math; it is not Minecraft or Android gameplay acceptance.
Requires system EGL/OpenGL and Pillow, with no new mod runtime dependencies.
"""
import argparse
import ctypes as c
import json
import struct
import time
from pathlib import Path
from PIL import Image
from test_solstice_shaders import ASSETS, MANIFEST, flatten


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--origin-x', type=float, default=0)
    parser.add_argument('--quality', choices=('low','medium','high','ultra'), default='ultra')
    parser.add_argument('--source', type=Path, help='Alternate Solstice common source for controlled before/after comparisons')
    parser.add_argument('--iterations', type=int, default=0, help='Optional timed draws after five warmup draws')
    args = parser.parse_args()
    width, height = 960, 540
    egl = c.CDLL('libEGL.so.1')

    def entry(library, name, result, *parameters):
        function = getattr(library, name)
        function.restype, function.argtypes = result, list(parameters)
        return function

    proc = entry(egl, 'eglGetProcAddress', c.c_void_p, c.c_char_p)
    platform = c.CFUNCTYPE(c.c_void_p, c.c_uint, c.c_void_p, c.POINTER(c.c_int))(proc(b'eglGetPlatformDisplayEXT'))
    display = platform(0x31DD, None, None)
    major, minor = c.c_int(), c.c_int()
    initialize = entry(egl, 'eglInitialize', c.c_uint, c.c_void_p, c.POINTER(c.c_int), c.POINTER(c.c_int))
    if not initialize(display, c.byref(major), c.byref(minor)):
        raise RuntimeError('Surfaceless EGL unavailable')
    entry(egl, 'eglBindAPI', c.c_uint, c.c_uint)(0x30A2)
    attributes = (c.c_int * 13)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
    config, count = c.c_void_p(), c.c_int()
    choose = entry(egl, 'eglChooseConfig', c.c_uint, c.c_void_p, c.POINTER(c.c_int), c.POINTER(c.c_void_p), c.c_int, c.POINTER(c.c_int))
    if not choose(display, attributes, c.byref(config), 1, c.byref(count)) or count.value < 1:
        raise RuntimeError('No OpenGL pbuffer configuration')
    create_context = entry(egl, 'eglCreateContext', c.c_void_p, c.c_void_p, c.c_void_p, c.c_void_p, c.POINTER(c.c_int))
    context = create_context(display, config, None, (c.c_int * 5)(0x3098, 4, 0x30FB, 5, 0x3038))
    create_surface = entry(egl, 'eglCreatePbufferSurface', c.c_void_p, c.c_void_p, c.c_void_p, c.POINTER(c.c_int))
    surface = create_surface(display, config, (c.c_int * 5)(0x3057, width, 0x3056, height, 0x3038))
    make_current = entry(egl, 'eglMakeCurrent', c.c_uint, c.c_void_p, c.c_void_p, c.c_void_p, c.c_void_p)
    if not context or not surface or not make_current(display, surface, surface, context):
        raise RuntimeError('Unable to make a test context current')

    def gl(name, result, *parameters):
        address = proc(name.encode())
        if not address:
            raise RuntimeError('Missing GL function: ' + name)
        return c.CFUNCTYPE(result, *parameters)(address)

    get_string = gl('glGetString', c.c_char_p, c.c_uint)
    print('Test renderer:', get_string(0x1F01).decode())
    shader_source = gl('glShaderSource', None, c.c_uint, c.c_int, c.POINTER(c.c_char_p), c.POINTER(c.c_int))
    compile_shader = gl('glCompileShader', None, c.c_uint)
    shader_status = gl('glGetShaderiv', None, c.c_uint, c.c_uint, c.POINTER(c.c_int))
    shader_log = gl('glGetShaderInfoLog', None, c.c_uint, c.c_int, c.POINTER(c.c_int), c.c_void_p)

    def shader(stage, text):
        result = gl('glCreateShader', c.c_uint, c.c_uint)(stage)
        encoded = c.c_char_p(text.encode())
        shader_source(result, 1, c.byref(encoded), None)
        compile_shader(result)
        status = c.c_int()
        shader_status(result, 0x8B81, c.byref(status))
        if not status.value:
            log = c.create_string_buffer(16384)
            shader_log(result, len(log), None, log)
            raise RuntimeError(log.value.decode())
        return result

    vertex = shader(0x8B31, '#version 450\nvoid main(){vec2 uv=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(uv*2-1,0,1);}')
    common = flatten((args.source or (ASSETS / 'greenmarine/shaders/include/solstice.glsl')).read_text())
    # Model the actual two 8-bit output boundaries (world target, final composition).
    first_dither = 'solOutputDither(encoded,gl_FragCoord.xy)' if 'solOutputDither' in common else 'encoded'
    last_dither = 'solOutputDither(displayed,gl_FragCoord.xy)' if 'solOutputDither' in common else 'displayed'
    fragment = shader(0x8B30, '#version 450\n' + common + '''
        layout(location=0) out vec4 fragColor;
        void main() {
            vec2 uv=gl_FragCoord.xy/Frame.xy;
            vec3 direction=normalize(vec3((uv.x-.5)*3.0,(uv.y-.12)*1.6,1));
            vec3 encoded=solDisplay(solSky(direction,true));
            encoded=FIRST_DITHER;
            vec3 quantized=floor(clamp(encoded,0.0,1.0)*255.0+.5)/255.0;
            vec3 displayed=solTone(solLinear(quantized));
            fragColor=vec4(LAST_DITHER,1);
        }
        '''.replace('FIRST_DITHER', first_dither).replace('LAST_DITHER', last_dither))
    program = gl('glCreateProgram', c.c_uint)()
    attach = gl('glAttachShader', None, c.c_uint, c.c_uint)
    attach(program, vertex); attach(program, fragment)
    gl('glLinkProgram', None, c.c_uint)(program)
    linked = c.c_int()
    gl('glGetProgramiv', None, c.c_uint, c.c_uint, c.POINTER(c.c_int))(program, 0x8B82, c.byref(linked))
    if not linked.value:
        raise RuntimeError('Test program failed to link')
    gl('glUseProgram', None, c.c_uint)(program)
    frame = [0.0] * 72
    frame[:8] = [width, height, 60, .25, 0, ('low','medium','high','ultra').index(args.quality), 0, 0]
    for start in (8, 24):
        for i in range(4): frame[start + i * 5] = 1
    parameters = json.loads(MANIFEST.read_text())['parameters']
    for i, definition in enumerate(parameters.values()): frame[40+i] = definition['default']
    surface_data = [0.0] * 32
    surface_data[4] = args.origin_x
    surface_data[8:12] = [.3, .8, .519615, 1]
    surface_data[12:16] = [.25, .45, .7, 1]
    environment = [0.0] * 32
    environment[:4] = [68, 0, 0, 0]
    environment[8:12] = [.45, .65, .8, 0]
    environment[24:28] = [1, 0, 0, 1]
    environment[28:32] = [60, .25, 0, 0]
    generate = gl('glGenBuffers', None, c.c_int, c.POINTER(c.c_uint))
    bind = gl('glBindBuffer', None, c.c_uint, c.c_uint)
    upload = gl('glBufferData', None, c.c_uint, c.c_ssize_t, c.c_void_p, c.c_uint)
    block_index = gl('glGetUniformBlockIndex', c.c_uint, c.c_uint, c.c_char_p)
    for binding, (name, values) in enumerate((('MarineFrame', frame), ('MarineSurfaceFrame', surface_data), ('MarineEnvironment', environment))):
        index = block_index(program, name.encode())
        if index == 0xFFFFFFFF:
            continue
        gl('glUniformBlockBinding', None, c.c_uint, c.c_uint, c.c_uint)(program, index, binding)
        buffer = c.c_uint(); generate(1, c.byref(buffer)); bind(0x8A11, buffer)
        data = c.create_string_buffer(struct.pack('<' + 'f' * len(values), *values))
        upload(0x8A11, len(values)*4, data, 0x88E4)
        gl('glBindBufferBase', None, c.c_uint, c.c_uint, c.c_uint)(0x8A11, binding, buffer)
    vao = c.c_uint()
    gl('glGenVertexArrays', None, c.c_int, c.POINTER(c.c_uint))(1, c.byref(vao))
    gl('glBindVertexArray', None, c.c_uint)(vao)
    gl('glViewport', None, c.c_int, c.c_int, c.c_int, c.c_int)(0, 0, width, height)
    gl('glDisable', None, c.c_uint)(0x0BD0)  # Explicit shader dither only.
    gl('glDrawArrays', None, c.c_uint, c.c_int, c.c_int)(4, 0, 3)
    if args.iterations > 0:
        draw = gl('glDrawArrays', None, c.c_uint, c.c_int, c.c_int)
        finish = gl('glFinish', None)
        for _ in range(5): draw(4, 0, 3)
        finish()
        query = c.c_uint()
        gl('glGenQueries', None, c.c_int, c.POINTER(c.c_uint))(1, c.byref(query))
        gl('glBeginQuery', None, c.c_uint, c.c_uint)(0x88BF, query)
        cpu_start = time.thread_time_ns()
        for _ in range(args.iterations): draw(4, 0, 3)
        cpu_nanos = time.thread_time_ns() - cpu_start
        gl('glEndQuery', None, c.c_uint)(0x88BF)
        elapsed = c.c_uint64()
        gl('glGetQueryObjectui64v', None, c.c_uint, c.c_uint, c.POINTER(c.c_uint64))(query, 0x8866, c.byref(elapsed))
        print(f'Controlled sky timing: quality={args.quality}, iterations={args.iterations}, '
              f'GPU timer ms/draw={elapsed.value / args.iterations / 1e6:.4f}, '
              f'calling-thread CPU ms/draw={cpu_nanos / args.iterations / 1e6:.4f}')
        gl('glDeleteQueries', None, c.c_int, c.POINTER(c.c_uint))(1, c.byref(query))
    pixels = (c.c_ubyte * (width * height * 4))()
    gl('glReadPixels', None, c.c_int, c.c_int, c.c_int, c.c_int, c.c_uint, c.c_uint, c.c_void_p)(0, 0, width, height, 0x1908, 0x1401, pixels)
    error = gl('glGetError', c.c_uint)()
    if error:
        raise RuntimeError(f'GL error: {error:#x}')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    Image.frombytes('RGBA', (width, height), bytes(pixels)).transpose(Image.Transpose.FLIP_TOP_BOTTOM).save(args.output)
    print(args.output)
    make_current(display, None, None, None)
    entry(egl, 'eglDestroyContext', c.c_uint, c.c_void_p, c.c_void_p)(display, context)
    entry(egl, 'eglDestroySurface', c.c_uint, c.c_void_p, c.c_void_p)(display, surface)
    entry(egl, 'eglTerminate', c.c_uint, c.c_void_p)(display)


if __name__ == '__main__':
    main()
