package dev.cinemar2.client;

import java.lang.reflect.Method;
import org.slf4j.LoggerFactory;

/** Optional Iris public API integration; Iris is never a required client dependency. */
final class IrisCompatibility {
    private static final org.slf4j.Logger LOGGER=LoggerFactory.getLogger("cinemar2");
    private static Object api;
    private static Method shadowPass;
    private static Method shadersActive;

    static {
        try{
            var loader=IrisCompatibility.class.getClassLoader();
            var apiType=Class.forName("net.irisshaders.iris.api.v0.IrisApi",true,loader);
            Object instance=apiType.getMethod("getInstance").invoke(null);
            shadowPass=apiType.getMethod("isRenderingShadowPass");
            shadersActive=apiType.getMethod("isShaderPackInUse");
            api=instance;
            LOGGER.info("Enabled optional Iris screen compatibility");
        }catch(ClassNotFoundException ignored){
            // Vanilla and Sodium-only clients use the original unlit shader.
        }catch(ReflectiveOperationException|LinkageError e){
            LOGGER.warn("Could not initialize optional Iris screen compatibility",e);
        }
    }

    static boolean shadowPass(){
        if(api==null)return false;
        try{return (boolean)shadowPass.invoke(api);}
        catch(ReflectiveOperationException e){
            api=null;LOGGER.warn("Could not query the Iris shadow pass",e);return false;
        }
    }

    static boolean shadersActive(){
        if(api==null)return false;
        try{return (boolean)shadersActive.invoke(api);}
        catch(ReflectiveOperationException e){
            api=null;LOGGER.warn("Could not query the Iris shader state",e);return false;
        }
    }
}
