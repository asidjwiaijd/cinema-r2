package dev.cinemar2.client;

import java.lang.reflect.Method;
import org.slf4j.LoggerFactory;

/** Optional Iris public API integration; Iris is never a required client dependency. */
final class IrisCompatibility {
    private static final org.slf4j.Logger LOGGER=LoggerFactory.getLogger("cinemar2");
    private static Object api;
    private static Method shadowPass;
    private static Method shadersActive;
    private static boolean queryFailed;

    static {
        try{
            var loader=IrisCompatibility.class.getClassLoader();
            var apiType=Class.forName("net.irisshaders.iris.api.v0.IrisApi",true,loader);
            Object instance=apiType.getMethod("getInstance").invoke(null);
            shadowPass=apiType.getMethod("isRenderingShadowPass");
            shadersActive=apiType.getMethod("isShaderPackInUse");
            api=instance;
            String revision="unknown";
            try{revision=String.valueOf(apiType.getMethod("getMinorApiRevision").invoke(instance));}
            catch(ReflectiveOperationException ignored){}
            LOGGER.info("Enabled optional Iris screen compatibility (API revision {})",revision);
        }catch(ClassNotFoundException ignored){
            LOGGER.info("Iris not installed; using the unlit screen pipeline");
        }catch(ReflectiveOperationException|LinkageError e){
            LOGGER.warn("Could not initialize optional Iris screen compatibility",e);
        }
    }

    static boolean shadowPass(){
        if(api==null||queryFailed)return false;
        try{return (boolean)shadowPass.invoke(api);}
        catch(ReflectiveOperationException e){report(e);return false;}
    }

    static boolean shadersActive(){
        if(api==null)return false;
        // A pack may be active even when the API stopped answering, and the
        // vanilla compatible path is correct with and without shaders.
        if(queryFailed)return true;
        try{return (boolean)shadersActive.invoke(api);}
        catch(ReflectiveOperationException e){report(e);return true;}
    }

    private static void report(ReflectiveOperationException e){
        if(queryFailed)return;
        queryFailed=true;
        LOGGER.warn("Iris compatibility queries failed; using the vanilla-compatible screen path",e);
    }
}
