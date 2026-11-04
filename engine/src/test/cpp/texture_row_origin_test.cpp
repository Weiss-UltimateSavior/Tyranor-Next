#include <limits>

#include "../../main/cpp/texture_row_origin.h"

struct Point { float x,y,u,v; };
constexpr Point bottom[]={{0,0,0,0},{1,0,1,0},{0,1,0,1},{1,1,1,1}};
constexpr Point top[]={{0,0,0,1},{1,0,1,1},{0,1,0,0},{1,1,1,0}};
constexpr Point crop[]={{8,9,.2f,.8f},{18,9,.7f,.8f},{8,29,.2f,.3f},{18,29,.7f,.3f}};
constexpr Point rotated[]={{0,0,0,0},{1,0,0,1},{0,1,1,0},{1,1,1,1}};
constexpr Point duplicate[]={{0,0,0,0},{1,0,1,0},{0,1,0,1},{0,1,0,1}};
constexpr Point nonfinite[]={{0,0,0,0},{1,0,1,0},{0,1,0,1},{1,std::numeric_limits<float>::quiet_NaN(),1,1}};
static_assert(textureRowOrigin(bottom,0,0,1,1)==0);
static_assert(textureRowOrigin(top,0,0,1,1)==1);
static_assert(textureRowOrigin(crop,.2f,.3f,.7f,.8f)==1);
static_assert(textureRowOrigin(rotated,0,0,1,1)==-1);
static_assert(textureRowOrigin(duplicate,0,0,1,1)==-1);
static_assert(textureRowOrigin(nonfinite,0,0,1,1)==-1);
static_assert(textureRowOrigin(bottom,0,0,0,1)==-1);
static_assert(textureRowOrigin<Point>(nullptr,0,0,1,1)==-1);
