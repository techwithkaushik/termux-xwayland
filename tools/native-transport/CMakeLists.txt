cmake_minimum_required(VERSION 3.22)
project(xw11_native_transport LANGUAGES C CXX)

include(CTest)
find_package(Threads REQUIRED)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# This library is intentionally independent of the legacy X.Org/Lorie CMake
# graph. It contains only portable transport/lifecycle code; Android Surface
# presentation remains in the Android-only adapter compiled by the app.
add_library(xw11_native_transport STATIC
    xw11_buffer_descriptor.cpp
    xw11_buffer_registry.cpp
    xw11_buffer_wire.cpp
    xw11_buffer_transport.cpp
    xw11_fd_passing.cpp
    xw11_input_dispatcher.cpp
    xw11_input_event.cpp
    xw11_input_queue.cpp
    xw11_input_receiver.cpp
    xw11_input_transform.cpp
    xw11_input_transport.cpp
    xw11_output_frame.cpp
    xw11_surface_generation.cpp
    xw11_transport_io.cpp
    xw11_transport_protocol.cpp
)
target_include_directories(xw11_native_transport PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(xw11_native_transport PUBLIC Threads::Threads)
target_compile_options(xw11_native_transport PRIVATE -Wall -Wextra -Werror)

function(xw11_add_test target source)
    add_executable(${target} "tests/${source}")
    target_link_libraries(${target} PRIVATE xw11_native_transport)
    target_compile_options(${target} PRIVATE -Wall -Wextra -Werror)
    add_test(NAME ${target} COMMAND ${target})
endfunction()

xw11_add_test(test_protocol test_protocol.cpp)
xw11_add_test(test_transport_io test_transport_io.cpp)
xw11_add_test(test_fd_passing test_fd_passing.cpp)
xw11_add_test(test_surface_generation test_surface_generation.cpp)
xw11_add_test(test_buffer_descriptor test_buffer_descriptor.cpp)
xw11_add_test(test_buffer_wire test_buffer_wire.cpp)
xw11_add_test(test_buffer_transport test_buffer_transport.cpp)
xw11_add_test(test_buffer_registry test_buffer_registry.cpp)
xw11_add_test(test_input_transform test_input_transform.cpp)
xw11_add_test(test_input_event test_input_event.cpp)
xw11_add_test(test_input_queue test_input_queue.cpp)
xw11_add_test(test_input_transport test_input_transport.cpp)
xw11_add_test(test_input_receiver test_input_receiver.cpp)
xw11_add_test(test_input_dispatcher test_input_dispatcher.cpp)
xw11_add_test(test_output_frame test_output_frame.cpp)
