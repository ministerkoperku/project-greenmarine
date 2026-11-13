#!/usr/bin/env python3
"""Execute actual relief gradient/output statements under equivalent UV rotations.

Texture and tangent basis rotate together, preserving the physical heightfield.
This CPU shader-policy fixture does not launch or modify Minecraft.
"""
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHADER = ROOT / 'src/main/resources/assets/greenmarine/shaders/include/solstice_terrain.glsl'


def compile_probe(directory):
    source = SHADER.read_text()
    start = source.index('ivec2 texel=', source.index('strength*=footprint;'))
    end = source.index('vec3 dpyPerp=', start)
    gradient = source[start:end]
    output = 'return normalize(normal-(tangent*gradient.x+bitangent*gradient.y)*scale*strength*2.0);'
    assert output in source
    program = r'''
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <algorithm>
struct vec2 {
    float x,y;
    explicit vec2(float a):x(a),y(a){}
    vec2(float x,float y):x(x),y(y){}
    vec2 operator*(vec2 b)const{return{x*b.x,y*b.y};}
    vec2 operator/(vec2 b)const{return{x/b.x,y/b.y};}
};
struct ivec2 {
    int x,y;
    explicit ivec2(int a):x(a),y(a){}
    ivec2(int x,int y):x(x),y(y){}
    explicit ivec2(vec2 a):x(int(a.x)),y(int(a.y)){}
    ivec2 operator&(int b)const{return{x&b,y&b};}
    ivec2 operator+(ivec2 b)const{return{x+b.x,y+b.y};}
    ivec2 operator-(ivec2 b)const{return{x-b.x,y-b.y};}
};
struct vec3 {
    float x,y,z;
    vec3(float x,float y,float z):x(x),y(y),z(z){}
    vec3 operator+(vec3 b)const{return{x+b.x,y+b.y,z+b.z};}
    vec3 operator-(vec3 b)const{return{x-b.x,y-b.y,z-b.z};}
    vec3 operator*(float b)const{return{x*b,y*b,z*b};}
};
vec2 floor(vec2 a){return{std::floor(a.x),std::floor(a.y)};}
vec2 max(vec2 a,vec2 b){return{std::max(a.x,b.x),std::max(a.y,b.y)};}
ivec2 clamp(ivec2 a,ivec2 lo,ivec2 hi){return{std::clamp(a.x,lo.x,hi.x),std::clamp(a.y,lo.y,hi.y)};}
float dot(vec3 a,vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
vec3 normalize(vec3 a){return a*(1/std::sqrt(dot(a,a)));}
int rotation=0,fetches=0,invalid=0,fixture=0,centreX=7,centreY=6;
float solTexelHeight(ivec2 p){
    ++fetches;
    // Adjacent atlas sprites are deliberately foreign. No probe may read them.
    if(p.x<16 || p.x>=32 || p.y<16 || p.y>=32){++invalid;return 1;}
    p.x-=16;p.y-=16;
    int x=p.x,y=p.y;
    if(rotation==1){x=p.y;y=15-p.x;}
    if(rotation==2){x=15-p.x;y=15-p.y;}
    if(rotation==3){x=15-p.y;y=p.x;}
    if(fixture==2)return .5f;
    if(x==centreX && y==centreY)return fixture==7?-1:.5f;
    if(x==centreX-1 && y==centreY)return fixture==1?.5f:(fixture==6?-1:.2f);
    if(x==centreX+1 && y==centreY)return fixture==5 || fixture==6?-1:.9f;
    if(x==centreX && y==centreY-1)return fixture==1?.5f:.3f;
    if(x==centreX && y==centreY+1)return .8f;
    return .5f;
}
vec3 evaluate(int turn){
    rotation=turn;fetches=invalid=0;
    centreX=fixture==3 || fixture==4?0:7;
    centreY=fixture==4?0:6;
    int tx=centreX,ty=centreY;
    if(turn==1){tx=15-centreY;ty=centreX;}
    if(turn==2){tx=15-centreX;ty=15-centreY;}
    if(turn==3){tx=centreY;ty=15-centreX;}
    vec2 uv((16+tx+.5f)/32,(16+ty+.5f)/32),size(32,32);
    vec3 normal(0,1,0);
''' + gradient + r'''
    const vec3 tangents[4]={{1,0,0},{0,0,-1},{-1,0,0},{0,0,1}};
    const vec3 bitangents[4]={{0,0,1},{1,0,0},{0,0,-1},{-1,0,0}};
    vec3 tangent=tangents[turn],bitangent=bitangents[turn];
    float scale=1,strength=1;
''' + output + r'''
}
int main(int argc,char** argv){
    if(argc!=2)return 2;fixture=std::atoi(argv[1]);
    for(int turn=0;turn<4;turn++){
        vec3 result=evaluate(turn);
        std::printf("%d %.9f %.9f %.9f %d %d\n",turn*90,result.x,result.y,result.z,fetches,invalid);
    }
}
'''
    cpp = Path(directory) / 'probe.cpp'
    binary = Path(directory) / 'probe'
    cpp.write_text(program)
    subprocess.run(['c++', '-std=c++17', str(cpp), '-o', str(binary)], check=True)
    return binary


def sample(binary, fixture):
    result = subprocess.run([str(binary), str(fixture)], capture_output=True, text=True, check=True)
    return [tuple(float(value) for value in row.split()) for row in result.stdout.splitlines()]


def main():
    with tempfile.TemporaryDirectory(prefix='solstice-relief-orientation-') as directory:
        binary = compile_probe(directory)
        same = True
        for fixture, name in enumerate(('asymmetric', 'flat-backward', 'flat', 'tile-edge', 'tile-corner',
                                       'alpha-neighbor', 'alpha-both', 'alpha-center')):
            values = sample(binary, fixture)
            reference = values[0][1:4]
            for turn, nx, ny, nz, fetches, invalid in values:
                delta = sum((a-b)**2 for a, b in zip((nx, ny, nz), reference))**.5
                print(f'fixture={name} rotation={turn:g} normal=({nx:.7f},{ny:.7f},{nz:.7f}) difference={delta:.7f} fetches={fetches:g} invalid={invalid:g}')
                same = same and delta < 1e-6 and invalid == 0
        return 0 if same else 1


if __name__ == '__main__':
    raise SystemExit(main())
