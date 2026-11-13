#ifndef EMBOSS_H
#define EMBOSS_H

#if defined( GL_ES ) && !defined( GL_EXT_texture_array )
#ifndef sampler2DArray
#define sampler2DArray sampler2D
#define texture2DArray( s, c ) texture2D( (s), (c).xy )
#endif
#endif

const vec3 factor = vec3( 0.27, 0.67, 0.06 );

vec4 EmbossSample( sampler2D tex, vec2 coord, vec2 step )
{
    return texture2D( tex, coord + vec2(-0.5, -0.5) * step ) * 2.0 +
           texture2D( tex, coord ) * -1.0 +
           texture2D( tex, coord + vec2(0.5, 0.5) * step ) * -1.0;
}

#if defined( BMODEL_MULTI_LAYERS )
vec4 EmbossSampleArray( sampler2DArray tex, vec2 coord, float layer, vec2 step )
{
    return texture2DArray( tex, vec3( coord + vec2(-0.5, -0.5) * step, layer ) ) * 2.0 +
           texture2DArray( tex, vec3( coord, layer ) ) * -1.0 +
           texture2DArray( tex, vec3( coord + vec2(0.5, 0.5) * step, layer ) ) * -1.0;
}
#endif

vec3 EmbossFilter( sampler2D ColorMap, vec2 texcoord, float EmbossScale )
{
#if defined( GLSL_gpu_shader4 )
    vec2 pstep = vec2( EmbossScale ) / vec2( textureSize( ColorMap, 0 ) );
#else
    vec2 pstep = vec2( EmbossScale ) * ( 1.0 / 512.0 );
#endif
    vec4 res = vec4( 0.5 );
    res += EmbossSample( ColorMap, texcoord, pstep );

    vec3 emboss = vec3( dot( factor, vec3( res ) ) );
    emboss *= 2.0;

    return emboss;
}

#if defined( BMODEL_MULTI_LAYERS )
vec3 EmbossFilterTerrain( sampler2DArray ColorMap, vec2 texcoord, vec4 mask0, vec4 mask1, vec4 mask2, vec4 mask3, float EmbossScale )
{
#if defined( GLSL_gpu_shader4 )
    vec2 pstep = vec2( EmbossScale ) / vec2( textureSize( ColorMap, 0 ).xy );
#else
    vec2 pstep = vec2( EmbossScale ) * ( 1.0 / 512.0 );
#endif
    vec4 res = vec4( 0.5 );
    vec3 emboss = vec3( 0.0 );

#if (TERRAIN_NUM_LAYERS >= 1)
    if( mask0.r > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 0.0, pstep ) * mask0.r;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 2
    if( mask0.g > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 1.0, pstep ) * mask0.g;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 3
    if( mask0.b > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 2.0, pstep ) * mask0.b;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 4
    if( mask0.a > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 3.0, pstep ) * mask0.a;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 5
    if( mask1.r > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 4.0, pstep ) * mask1.r;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 6
    if( mask1.g > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 5.0, pstep ) * mask1.g;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 7
    if( mask1.b > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 6.0, pstep ) * mask1.b;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 8
    if( mask1.a > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 7.0, pstep ) * mask1.a;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 9
    if( mask0.r > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 8.0, pstep ) * mask2.r;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 10
    if( mask0.g > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 9.0, pstep ) * mask2.g;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 11
    if( mask0.b > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 10.0, pstep ) * mask2.b;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 12
    if( mask0.a > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 11.0, pstep ) * mask2.a;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 13
    if( mask1.r > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 12.0, pstep ) * mask3.r;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 14
    if( mask1.g > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 13.0, pstep ) * mask3.g;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 15
    if( mask1.b > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 14.0, pstep ) * mask3.b;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

#if TERRAIN_NUM_LAYERS >= 16
    if( mask1.a > 0.0 )
    {
        res += EmbossSampleArray( ColorMap, texcoord, 15.0, pstep ) * mask3.a;
        emboss = vec3( dot( factor, vec3( res ) ) );
    }
#endif

    emboss *= 2.0;

    return emboss;
}
#endif // /BMODEL_MULTI_LAYERS

#endif//EMBOSS_H
