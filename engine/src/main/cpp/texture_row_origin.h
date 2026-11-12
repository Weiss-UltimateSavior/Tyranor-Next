#pragma once
#include <limits>

// Cocos sprite-local Y increases upwards. Infer texture row order before the
// sprite's display transform; a deliberate display rotation must not rotate the CNN kernel.
template<class Vertex>
constexpr int textureRowOrigin(const Vertex* vertices, float u0, float v0, float u1, float v1) {
    if (!vertices || !(u1 > u0) || !(v1 > v0)) return -1;
    auto abs=[](float value){return value<0?-value:value;};
    auto finite=[&](float value){return abs(value)<=std::numeric_limits<float>::max();};
    float left = vertices[0].x, right = left, bottom = vertices[0].y, top = bottom;
    for (int i=0;i<4;++i) {
        const auto& p=vertices[i];
        if (!finite(p.x) || !finite(p.y) || !finite(p.u) || !finite(p.v)) return -1;
        left=(left<p.x?left:p.x);right=(right>p.x?right:p.x);
        bottom=(bottom<p.y?bottom:p.y);top=(top>p.y?top:p.y);
    }
    if (!(right>left) || !(top>bottom)) return -1;
    int mask=0, origin=-1;
    for (int i=0;i<4;++i) {
        const auto& p=vertices[i];
        const float x=(p.x-left)/(right-left), y=(p.y-bottom)/(top-bottom);
        const float u=(p.u-u0)/(u1-u0), v=(p.v-v0)/(v1-v0);
        auto near=[&](float a,float b){return abs(a-b)<0.0001f;};
        if ((!near(x,0)&&!near(x,1)) || (!near(y,0)&&!near(y,1)) || !near(x,u)) return -1;
        int current=near(y,v)?0:near(y,1-v)?1:-1;
        if(current<0 || (origin>=0 && current!=origin))return -1;
        origin=current; mask|=1<<((x>0.5f?1:0)+(y>0.5f?2:0));
    }
    return mask==15?origin:-1;
}
