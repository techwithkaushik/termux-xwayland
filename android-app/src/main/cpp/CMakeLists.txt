cmake_minimum_required(VERSION 3.22.1)
project(termuxxwayland_renderer LANGUAGES CXX)

# CMakeLists.txt is already located in android-app/src/main/cpp.
add_library(termuxxwayland_renderer SHARED native_egl_renderer.cpp)
target_compile_features(termuxxwayland_renderer PRIVATE cxx_std_17)
target_compile_options(termuxxwayland_renderer PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(termuxxwayland_renderer PRIVATE android log EGL GLESv2)
