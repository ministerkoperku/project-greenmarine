// Triangle tint.w is additive sunlight transmission; legacy metadata keeps zero.
float traceSunTransmission(vec3 origin,vec3 direction,float reach) {
    rayQueryEXT ray;
    rayQueryInitializeEXT(ray,World,gl_RayFlagsNoneEXT,255,origin,.015,direction,reach);
    float transmission=1.0;
    while(rayQueryProceedEXT(ray)) {
        if(rayQueryGetIntersectionTypeEXT(ray,false)==gl_RayQueryCandidateIntersectionTriangleEXT) {
            uint index=rayQueryGetIntersectionPrimitiveIndexEXT(ray,false);
            Triangle material=marineFetchTriangle(rayQueryGetIntersectionInstanceCustomIndexEXT(ray,false),index);
            if(material.uv2.z>=.5 && triangleSurface(material,rayQueryGetIntersectionBarycentricsEXT(ray,false)).a<=.1) continue;
            transmission*=clamp(material.tint.w,0.0,1.0);
            if(transmission<.01) {
                rayQueryTerminateEXT(ray);
                return 0.0;
            }
        }
    }
    return transmission;
}
