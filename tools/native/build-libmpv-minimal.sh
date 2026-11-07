#!/usr/bin/env bash
# Windows x86-64 cross-build on Ubuntu/WSL. No root access or production data needed.
set -euo pipefail
recipe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$recipe_dir/../.." && pwd)"
work="${CINEMA_NATIVE_BUILD_ROOT:-$HOME/cinema-native-build-20261022}"
jobs="${CINEMA_NATIVE_JOBS:-8}"
prefix="$work/prefix"
mkdir -p "$work" "$prefix/bin" "$work/build"
python3 "$recipe_dir/prepare_sources.py" --work "$work" --cache "$repo_dir/.tools/native-build/archives"
if git -C "$work/src/mpv" apply --check "$recipe_dir/mpv-minimal.patch" 2>/dev/null; then
    git -C "$work/src/mpv" apply "$recipe_dir/mpv-minimal.patch"
else
    git -C "$work/src/mpv" apply --reverse --check "$recipe_dir/mpv-minimal.patch"
fi
for native_patch in "$recipe_dir"/ffmpeg-*.patch; do
    if git -C "$work/src/ffmpeg" apply --check "$native_patch" 2>/dev/null; then
        git -C "$work/src/ffmpeg" apply "$native_patch"
    else
        git -C "$work/src/ffmpeg" apply --reverse --check "$native_patch"
    fi
done
if [[ ! -f "$work/python/mesonbuild/mesonmain.py" ]]; then
    python3 -m pip install --target "$work/python" meson==1.9.2 mako==1.3.10 jinja2==3.1.6 MarkupSafe==3.0.4
fi
export PYTHONPATH="$work/python"
export PATH="$prefix/bin:$work/python/bin:$PATH"
if [[ -L "$prefix/bin/dlltool" ]]; then rm -- "$prefix/bin/dlltool"; fi
cat > "$prefix/bin/dlltool" <<'EOF'
#!/bin/sh
exec /usr/bin/x86_64-w64-mingw32-dlltool -S /usr/bin/x86_64-w64-mingw32-as "$@"
EOF
chmod +x "$prefix/bin/dlltool"
export PKG_CONFIG_PATH=""
export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
cat > "$prefix/bin/cinema-pkg-config" <<EOF
#!/bin/sh
export PKG_CONFIG_PATH=''
export PKG_CONFIG_LIBDIR='$prefix/lib/pkgconfig'
exec /usr/bin/pkg-config --static "\$@"
EOF
chmod +x "$prefix/bin/cinema-pkg-config"
cat > "$work/cross.ini.next" <<EOF
[binaries]
c = 'x86_64-w64-mingw32-gcc'
cpp = 'x86_64-w64-mingw32-g++'
ar = 'x86_64-w64-mingw32-ar'
strip = 'x86_64-w64-mingw32-strip'
windres = 'x86_64-w64-mingw32-windres'
pkg-config = '$prefix/bin/cinema-pkg-config'
[host_machine]
system = 'windows'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
[properties]
needs_exe_wrapper = true
[built-in options]
buildtype = 'minsize'
default_library = 'static'
prefer_static = true
c_args = ['-ffunction-sections', '-fdata-sections', '-march=x86-64', '-mtune=generic', '-I$prefix/include']
cpp_args = ['-ffunction-sections', '-fdata-sections', '-march=x86-64', '-mtune=generic', '-I$prefix/include']
c_link_args = ['-static', '-static-libgcc', '-static-libstdc++', '-Wl,--gc-sections', '-L$prefix/lib']
cpp_link_args = ['-static', '-static-libgcc', '-static-libstdc++', '-Wl,--gc-sections', '-L$prefix/lib']
EOF
if ! cmp -s "$work/cross.ini.next" "$work/cross.ini"; then cp "$work/cross.ini.next" "$work/cross.ini"; fi
meson_build() {
    local name="$1"; shift
    local fingerprint
    fingerprint="$(printf '%s\n' "$*" "$(sha256sum "$work/cross.ini" "$recipe_dir/sources.lock.json")" | sha256sum | cut -d' ' -f1)"
    if [[ -f "$work/build/$name/.cinema-installed" ]] && [[ "$(cat "$work/build/$name/.cinema-installed")" == "$fingerprint" ]]; then
        echo "=== Reuse verified $name ==="
        # Relink mpv when a static dependency changed, even if its own options did not.
        if [[ "$name" == mpv ]]; then ninja -C "$work/build/$name" -j "$jobs"; ninja -C "$work/build/$name" install; fi
        return
    fi
    echo "=== Configure $name ==="
    local configure_mode=()
    if [[ -f "$work/build/$name/build.ninja" ]]; then configure_mode=(--reconfigure --clearcache); fi
    python3 -m mesonbuild.mesonmain setup "${configure_mode[@]}" "$work/build/$name" "$work/src/$name" \
        --cross-file "$work/cross.ini" --prefix "$prefix" --libdir lib --wrap-mode=nodownload \
        -Dauto_features=disabled "$@"
    ninja -C "$work/build/$name" -j "$jobs"
    ninja -C "$work/build/$name" install
    printf '%s\n' "$fingerprint" > "$work/build/$name/.cinema-installed"
}

# zlib is the only compression dependency; build it from pinned source as well.
cmake -S "$work/src/zlib" -B "$work/build/zlib" -G Ninja \
    -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
    -DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCMAKE_BUILD_TYPE=MinSizeRel -DZLIB_BUILD_EXAMPLES=OFF
cmake --build "$work/build/zlib" -j "$jobs"
cmake --install "$work/build/zlib"
mkdir -p "$prefix/lib/pkgconfig"
cat > "$prefix/lib/pkgconfig/zlib.pc.next" <<EOF
prefix=$prefix
libdir=\${prefix}/lib
includedir=\${prefix}/include
Name: zlib
Description: Pinned static zlib for Cinema libmpv
Version: 1.3.1
Libs: -L\${libdir} -lzlibstatic
Cflags: -I\${includedir}
EOF
if ! cmp -s "$prefix/lib/pkgconfig/zlib.pc.next" "$prefix/lib/pkgconfig/zlib.pc"; then cp "$prefix/lib/pkgconfig/zlib.pc.next" "$prefix/lib/pkgconfig/zlib.pc"; fi
meson_build freetype -Dzlib=system -Dbrotli=disabled -Dbzip2=disabled -Dpng=disabled -Dharfbuzz=disabled
meson_build harfbuzz -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dicu=disabled \
    -Dtests=disabled -Ddocs=disabled -Dutilities=disabled -Dbenchmark=disabled
meson_build fribidi -Ddocs=false -Dbin=false -Dtests=false
meson_build libass -Ddirectwrite=enabled -Dfontconfig=disabled -Dlibunibreak=disabled -Dasm=enabled
meson_build dav1d -Denable_tools=false -Denable_tests=false -Denable_asm=true
# libplacebo is an upstream mandatory dependency. No GPU backend/shader compiler is needed
# for mpv_render_context_render("sw"); D3D hardware frame copy remains enabled in mpv.
cp -a "$work/src/vulkan-headers/include/." "$prefix/include/"
if [[ ! -d "$work/src/libplacebo/3rdparty/fast_float/include" ]]; then
    mkdir -p "$work/src/libplacebo/3rdparty/fast_float"
    cp -a "$work/src/fast_float/." "$work/src/libplacebo/3rdparty/fast_float/"
fi
meson_build libplacebo -Ddemos=false -Dvulkan=disabled -Dopengl=disabled -Dd3d11=disabled \
    -Dglslang=disabled -Dshaderc=disabled -Dlcms=disabled -Ddovi=disabled -Dlibdovi=disabled -Dxxhash=disabled

# SChannel cannot load mpv's existing tls-ca-file. OpenSSL retains CA-file support
# and uses the Windows system trust store for normal public HTTPS connections.
sslbuild="$work/build/openssl"
mkdir -p "$sslbuild"
if [[ ! -f "$sslbuild/.cinema-installed" ]]; then
    if [[ ! -f "$sslbuild/Makefile" ]]; then
        (cd "$sslbuild" && perl "$work/src/openssl/Configure" mingw64 \
            --cross-compile-prefix=x86_64-w64-mingw32- --prefix="$prefix" --libdir=lib \
            no-shared no-tests no-apps no-docs no-module no-legacy no-engine enable-winstore \
            -Os -ffunction-sections -fdata-sections)
    fi
    make -C "$sslbuild" -j "$jobs"
    make -C "$sslbuild" install_sw
    touch "$sslbuild/.cinema-installed"
fi

echo '=== Configure FFmpeg decoder-only runtime ==='
ffbuild="$work/build/ffmpeg"
mkdir -p "$ffbuild"
if [[ ! -f "$ffbuild/ffbuild/config.mak" ]]; then
    (cd "$ffbuild" && "$work/src/ffmpeg/configure" \
        --prefix="$prefix" --target-os=mingw32 --arch=x86_64 --cpu=x86-64 \
        --cross-prefix=x86_64-w64-mingw32- --enable-cross-compile --enable-runtime-cpudetect \
        --pkg-config="$prefix/bin/cinema-pkg-config" --pkg-config-flags=--static \
        --disable-autodetect --disable-shared --enable-static --enable-gpl --enable-version3 \
        --disable-programs --disable-doc --disable-debug --disable-avdevice \
        --disable-everything --enable-avcodec --enable-avformat --enable-avfilter \
        --enable-swresample --enable-swscale --enable-network --disable-pthreads --enable-w32threads \
        --enable-zlib --disable-schannel --enable-openssl --enable-d3d11va --enable-dxva2 --enable-libdav1d \
        --enable-decoder=h264,hevc,vp8,vp9,libdav1d,mpeg2video,mpeg4,mjpeg,png,webp,bmp,gif,tiff \
        --enable-decoder=aac,aac_latm,mp3,mp3float,flac,vorbis,opus,ac3,eac3,alac,wmav1,wmav2 \
        --enable-decoder=pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_f64le,pcm_u8 \
        --enable-decoder=ass,ssa,subrip,webvtt,movtext,dvdsub,pgssub \
        --enable-demuxer=hls,mpegts,mov,matroska,mpegvideo,mpegps,flv,aac,mp3,ogg,flac,wav,concat \
        --enable-demuxer=image2,image2pipe,png_pipe,jpeg_pipe,webp_pipe,bmp_pipe,gif,ass,srt,webvtt \
        --enable-protocol=file,http,https,tcp,tls,crypto,data,httpproxy,concat,subfile \
        --enable-filter=aresample,aformat,anull,atempo,volume,pan,abuffer,abuffersink,buffer,buffersink,format,scale,null,transpose,hflip,vflip \
        --enable-parsers --enable-bsfs --enable-hwaccels \
        --extra-cflags="-Os -ffunction-sections -fdata-sections -I$prefix/include" \
        --extra-ldflags="-static -static-libgcc -Wl,--gc-sections -L$prefix/lib")
fi
make -C "$ffbuild" -j "$jobs"
make -C "$ffbuild" install
meson_build mpv -Dlibmpv=true -Dcplayer=false -Dgpl=true -Dbuild-date=false \
    -Ddefault_library=shared -Dwasapi=enabled -Dgl=disabled -Dd3d11=disabled \
    -Dd3d-hwaccel=enabled -Dd3d9-hwaccel=enabled -Dwin32-threads=enabled -Dlua=disabled -Djavascript=disabled
mkdir -p "$work/output"
cp "$work/build/mpv/libmpv-2.dll" "$work/output/libmpv-2.dll"
x86_64-w64-mingw32-strip --strip-unneeded "$work/output/libmpv-2.dll"
x86_64-w64-mingw32-objdump -p "$work/output/libmpv-2.dll" | grep 'DLL Name:' | tee "$work/output/imports.txt"
sha256sum "$work/output/libmpv-2.dll" | tee "$work/output/libmpv-2.dll.sha256"
cp "$recipe_dir/sources.lock.json" "$work/output/"
echo "Build complete: $work/output"
