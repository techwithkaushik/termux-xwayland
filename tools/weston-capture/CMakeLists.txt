cmake_minimum_required(VERSION 3.22)
project(xw11_weston_capture LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
pkg_check_modules(WAYLAND_CLIENT REQUIRED IMPORTED_TARGET wayland-client)
pkg_check_modules(LIBWESTON REQUIRED IMPORTED_TARGET libweston-16)
find_program(WAYLAND_SCANNER wayland-scanner REQUIRED)

set(PROTOCOL_XML "${CMAKE_CURRENT_SOURCE_DIR}/weston-output-capture.xml")
set(PROTOCOL_HEADER "${CMAKE_CURRENT_BINARY_DIR}/weston-output-capture-client-protocol.h")
set(PROTOCOL_CODE "${CMAKE_CURRENT_BINARY_DIR}/weston-output-capture-protocol.c")
add_custom_command(OUTPUT "${PROTOCOL_HEADER}"
    COMMAND "${WAYLAND_SCANNER}" client-header "${PROTOCOL_XML}" "${PROTOCOL_HEADER}"
    DEPENDS "${PROTOCOL_XML}" VERBATIM)
add_custom_command(OUTPUT "${PROTOCOL_CODE}"
    COMMAND "${WAYLAND_SCANNER}" private-code "${PROTOCOL_XML}" "${PROTOCOL_CODE}"
    DEPENDS "${PROTOCOL_XML}" VERBATIM)

add_subdirectory("../native-transport" "${CMAKE_CURRENT_BINARY_DIR}/native-transport")
add_executable(xw11-weston-capture capture_main.c "${PROTOCOL_CODE}" "${PROTOCOL_HEADER}")
target_include_directories(xw11-weston-capture PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}" "../native-transport")
target_link_libraries(xw11-weston-capture PRIVATE
    xw11_native_transport PkgConfig::WAYLAND_CLIENT)
target_compile_options(xw11-weston-capture PRIVATE -Wall -Wextra -Wno-unused-parameter)

add_library(xw11-capture-authority MODULE capture_authority.c)
target_link_libraries(xw11-capture-authority PRIVATE PkgConfig::LIBWESTON)
target_compile_options(xw11-capture-authority PRIVATE -Wall -Wextra -Werror)
set_target_properties(xw11-capture-authority PROPERTIES PREFIX "" OUTPUT_NAME "xw11-capture-authority")
