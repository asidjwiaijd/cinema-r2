package dev.cinemar2.client;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;
import java.util.Map;

/** Small binding to the stable libmpv client/render C APIs. */
public interface NativeMpv extends Library {
    interface Update extends Callback { void invoke(Pointer context); }
    Pointer mpv_create();
    int mpv_initialize(Pointer handle);
    int mpv_set_option_string(Pointer handle,String name,String value);
    int mpv_set_property_string(Pointer handle,String name,String value);
    Pointer mpv_get_property_string(Pointer handle,String name);
    int mpv_get_property(Pointer handle,String name,int format,Pointer value);
    int mpv_command(Pointer handle,Pointer args);
    Pointer mpv_wait_event(Pointer handle,double timeout);
    void mpv_free(Pointer memory);
    void mpv_free_node_contents(Pointer node);
    void mpv_terminate_destroy(Pointer handle);
    String mpv_error_string(int error);
    int mpv_render_context_create(PointerByReference context,Pointer handle,Pointer params);
    void mpv_render_context_set_update_callback(Pointer context,Update callback,Pointer opaque);
    long mpv_render_context_update(Pointer context);
    int mpv_render_context_render(Pointer context,Pointer params);
    void mpv_render_context_free(Pointer context);
    static NativeMpv load(String path){return Native.load(path,NativeMpv.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));}
}
