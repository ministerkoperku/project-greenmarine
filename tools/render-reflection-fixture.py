#!/usr/bin/env python3
"""Controlled reconstruction of actual Solstice ray samples on an oblique mirror.

This is a synthetic GL fixture, not Minecraft/Android acceptance or an RT test.
Magenta marks a valid reflective pixel with no accepted reconstruction sample.
"""
import argparse
import ctypes as c
import struct,json
from array import array
from pathlib import Path
from PIL import Image
from test_solstice_shaders import ASSETS,flatten


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('--rays-source',type=Path)
    parser.add_argument('--scene',action='store_true',help='Verify that the scene pass preserves already shaded terrain')
    parser.add_argument('--distance',type=float,default=.15)
    parser.add_argument('--slope',type=float,default=.08)
    parser.add_argument('--divisor',type=int,default=4,choices=(2,4))
    parser.add_argument('--occluder',action='store_true',help='Add a closer parallel metal surface with a different reflected color')
    parser.add_argument('--check-confidence',action='store_true',help='Require partial producer coverage to fade native reflection')
    parser.add_argument('--probe-phase',type=float,default=0.0,help='Subpixel horizontal reconstruction probe offset')
    parser.add_argument('--check-diffuse',action='store_true',help='Require a continuous lighting probe across the native quarter-block seed boundary')
    args=parser.parse_args()
    width,height=320,180
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
    source=args.rays_source or (ASSETS/('greenmarine/shaders/include/solstice_scene.glsl' if args.scene else 'greenmarine/shaders/include/solstice_rays.glsl'))
    common=flatten(source.read_text())
    fragment_body='''
        layout(location=0) out vec4 fragColor;
        void main(){
            vec2 uv=gl_FragCoord.xy/Frame.xy;
            vec4 surface=texture(MarineSurfaceNormalDepth,uv),material=texture(MarineSurfaceAlbedo,uv);
            if(surface.w<=0.0 || floor(material.a)!=2.0){fragColor=vec4(0,0,0,1);return;}
            vec4 lighting,reflection;
            bool ready=solSurfaceRays(uv,surface,material,true,lighting,reflection);
            fragColor=ready?vec4(reflection.rgb*reflection.a,1):vec4(1,0,1,1);
        }
    '''
    if args.scene:
        fragment_body='layout(location=0) out vec4 fragColor; void main(){fragColor=vec4(solDisplay(solScene(gl_FragCoord.xy/Frame.xy)),1);}'
    elif args.probe_phase:
        fragment_body=fragment_body.replace('gl_FragCoord.xy/Frame.xy',f'(gl_FragCoord.xy+vec2({args.probe_phase:.8f},0.0))/Frame.xy')
    fragment=shader(0x8B30,'#version 450\n'+common+fragment_body)
    program=gl('glCreateProgram',c.c_uint)()
    for stage in [vertex,fragment]:gl('glAttachShader',None,c.c_uint,c.c_uint)(program,stage)
    gl('glLinkProgram',None,c.c_uint)(program)
    linked=c.c_int();gl('glGetProgramiv',None,c.c_uint,c.c_uint,c.POINTER(c.c_int))(program,0x8B82,c.byref(linked))
    if not linked.value:raise RuntimeError('Fixture link failed')
    gl('glUseProgram',None,c.c_uint)(program)
    aspect=width/height
    frame=[0.0]*72;frame[:8]=[width,height,0,0,1,1,2,0]
    frame[40+6]=1;frame[40+8]=1;frame[40+10]=1
    frame[8:24]=[aspect,0,0,0,0,1,0,0,0,0,0,1,0,0,1,0]
    for i in range(4):frame[24+i*5]=1
    environment=[0.0]*32;environment[11]=1
    ray_frame=[1,96,0,0,-96,-96,-96,0,96,96,96,0,0,0,0,0,.45,0,0,0]
    for binding,(name,values) in enumerate([('MarineFrame',frame),('MarineSurfaceFrame',[0.0]*32),('RaySurfaceFrame',[1,1,1,96]),('MarineEnvironment',environment),('RayFrame',ray_frame)]):
        index=gl('glGetUniformBlockIndex',c.c_uint,c.c_uint,c.c_char_p)(program,name.encode())
        if index==0xffffffff:continue
        gl('glUniformBlockBinding',None,c.c_uint,c.c_uint,c.c_uint)(program,index,binding)
        buffer=c.c_uint();gl('glGenBuffers',None,c.c_int,c.POINTER(c.c_uint))(1,c.byref(buffer))
        gl('glBindBuffer',None,c.c_uint,c.c_uint)(0x8a11,buffer)
        data=c.create_string_buffer(struct.pack('<'+'f'*len(values),*values))
        gl('glBufferData',None,c.c_uint,c.c_ssize_t,c.c_void_p,c.c_uint)(0x8a11,len(values)*4,data,0x88e4)
        gl('glBindBufferBase',None,c.c_uint,c.c_uint,c.c_uint)(0x8a11,binding,buffer)
    nx=(1-args.slope*args.slope)**.5;nz=args.slope
    normals=array('f');materials=array('f');valid=[];foregrounds=[]
    for y in range(height):
        for x in range(width):
            sx=((x+.5)/width*2-1)*aspect
            depth=(nx*sx+nz)/args.distance
            foreground=args.occluder and 0<depth<.45 and height*.28<y<height*.72
            mirror=0<depth<1 and not foreground
            normals.extend([-nx,0,-nz,depth*2 if foreground else depth] if mirror or foreground else [0,0,0,0])
            materials.extend([.6,.2,.08,2.2] if mirror or foreground else [0,0,0,1.45])
            valid.append(mirror)
            foregrounds.append(foreground)
    ow=(width-1)//args.divisor+1;oh=(height-1)//args.divisor+1
    rays=array('f');lights=array('f')
    for y in range(oh):
        for x in range(ow):
            cx=((x*2+1)*width)//(ow*2);cy=((y*2+1)*height)//(oh*2)
            hit=valid[cy*width+cx]
            rays.extend([.15,.7,1,1] if hit else ([1,0,0,1] if foregrounds[cy*width+cx] else [0,0,0,0]));lights.extend([1,1,1,1])
    textures=[('MarineSurfaceNormalDepth',width,height,normals),('MarineSurfaceAlbedo',width,height,materials),('RayReflection',ow,oh,rays),('RaySurfaceLighting',ow,oh,lights)]
    if args.scene:
        textures += [('SceneSampler',width,height,array('f',[.35,.35,.35,1])*(width*height)),
                     ('DepthSampler',width,height,array('f',(v for i in range(width*height) for v in [normals[i*4+3],0,0,0])))]
    for unit,(name,w,h,values) in enumerate(textures):
        texture=c.c_uint();gl('glGenTextures',None,c.c_int,c.POINTER(c.c_uint))(1,c.byref(texture))
        gl('glActiveTexture',None,c.c_uint)(0x84c0+unit);gl('glBindTexture',None,c.c_uint,c.c_uint)(0xde1,texture)
        for target,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812f),(0x2803,0x812f)]:
            gl('glTexParameteri',None,c.c_uint,c.c_uint,c.c_int)(0xde1,target,value)
        data=(c.c_float*len(values)).from_buffer(values)
        gl('glTexImage2D',None,c.c_uint,c.c_int,c.c_int,c.c_int,c.c_int,c.c_int,c.c_uint,c.c_uint,c.c_void_p)(0xde1,0,0x8814,w,h,0,0x1908,0x1406,data)
        location=gl('glGetUniformLocation',c.c_int,c.c_uint,c.c_char_p)(program,name.encode())
        gl('glUniform1i',None,c.c_int,c.c_int)(location,unit)
    vao=c.c_uint();gl('glGenVertexArrays',None,c.c_int,c.POINTER(c.c_uint))(1,c.byref(vao));gl('glBindVertexArray',None,c.c_uint)(vao)
    gl('glViewport',None,c.c_int,c.c_int,c.c_int,c.c_int)(0,0,width,height)
    gl('glDisable',None,c.c_uint)(0xbd0)
    gl('glDrawArrays',None,c.c_uint,c.c_int,c.c_int)(4,0,3)
    pixels=(c.c_ubyte*(width*height*4))()
    gl('glReadPixels',None,c.c_int,c.c_int,c.c_int,c.c_int,c.c_uint,c.c_uint,c.c_void_p)(0,0,width,height,0x1908,0x1401,pixels)
    error=gl('glGetError',c.c_uint)()
    if error:raise RuntimeError(f'GL error {error:#x}')
    missing=sum(1 for i,v in enumerate(valid) if v and pixels[i*4]>250 and pixels[i*4+1]<5 and pixels[i*4+2]>250) if not args.scene else None
    leaks=sum(1 for i,v in enumerate(valid) if v and pixels[i*4]>42 and pixels[i*4+2]<250) if not args.scene else None
    foreground_leaks=sum(1 for i,v in enumerate(foregrounds) if v and pixels[i*4+1]>3) if not args.scene else None
    scene_changed=sum(1 for i,v in enumerate(valid) if v and any(abs(pixels[i*4+j]-89)>3 for j in range(3))) if args.scene else None
    reflected_blue=[pixels[i*4+2] for i,v in enumerate(valid) if v and pixels[i*4+1]>1]
    blue_range=[min(reflected_blue),max(reflected_blue)] if reflected_blue else []
    if args.check_diffuse and not (blue_range and blue_range[1]-blue_range[0]<=1):
        raise AssertionError(f'Constant angular lighting must not jump at a world sampling-grid boundary: {blue_range}')
    if args.check_confidence and not (blue_range and 0<blue_range[0]<200 and blue_range[1]>250):
        raise AssertionError(f'Partial producer footprint must fade instead of becoming a full-strength island: {blue_range}')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    Image.frombytes('RGBA',(width,height),bytes(pixels)).transpose(Image.Transpose.FLIP_TOP_BOTTOM).save(args.output)
    print(json.dumps(dict(distance=args.distance,slope=args.slope,divisor=args.divisor,reflectivePixels=sum(valid),missing=missing,mirrorLeaks=leaks,foregroundPixels=sum(foregrounds),foregroundLeaks=foreground_leaks,sceneChanged=scene_changed,reflectionBlueRange=blue_range)))
    make_current(display,None,None,None)
    entry(egl,'eglDestroyContext',c.c_uint,c.c_void_p,c.c_void_p)(display,context)
    entry(egl,'eglDestroySurface',c.c_uint,c.c_void_p,c.c_void_p)(display,surface)
    entry(egl,'eglTerminate',c.c_uint,c.c_void_p)(display)


if __name__=='__main__':main()
