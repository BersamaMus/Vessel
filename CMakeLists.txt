cmake_minimum_required(VERSION 3.21)

project(BetterVideoPlayer VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.8 REQUIRED COMPONENTS Concurrent OpenGL Quick)
qt_standard_project_setup(REQUIRES 6.8)

# ---------------------------------------------------------------------------
# libmpv
# ---------------------------------------------------------------------------
if(WIN32)
    # Expects the shinchiro "mpv-dev-x86_64-*.7z" layout:
    #   <MPV_DIR>/include/mpv/*.h, <MPV_DIR>/libmpv-2.dll
    set(MPV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mpv" CACHE PATH "libmpv dev package directory")
    find_path(MPV_INCLUDE_DIR mpv/client.h PATHS "${MPV_DIR}/include" NO_DEFAULT_PATH REQUIRED)
    find_file(MPV_DLL libmpv-2.dll PATHS "${MPV_DIR}" NO_DEFAULT_PATH REQUIRED)

    if(MSVC)
        # The prebuilt package only ships a MinGW import library (libmpv.dll.a),
        # so generate an MSVC one from the DLL's export table.
        set(MPV_IMPLIB "${CMAKE_CURRENT_BINARY_DIR}/mpv.lib")
        if(NOT EXISTS "${MPV_IMPLIB}")
            get_filename_component(_msvc_bin "${CMAKE_CXX_COMPILER}" DIRECTORY)
            find_program(DUMPBIN_EXE dumpbin HINTS "${_msvc_bin}" REQUIRED)
            execute_process(COMMAND "${DUMPBIN_EXE}" /nologo /exports "${MPV_DLL}"
                            OUTPUT_VARIABLE _exports COMMAND_ERROR_IS_FATAL ANY)
            string(REGEX MATCHALL "[ \t](mpv_[A-Za-z0-9_]+)" _symbols "${_exports}")
            list(TRANSFORM _symbols STRIP)
            list(REMOVE_DUPLICATES _symbols)
            list(JOIN _symbols "\n    " _symbols)
            file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/mpv.def" "LIBRARY libmpv-2\nEXPORTS\n    ${_symbols}\n")
            execute_process(COMMAND "${CMAKE_AR}" /nologo /def:${CMAKE_CURRENT_BINARY_DIR}/mpv.def
                                    /machine:x64 /out:${MPV_IMPLIB}
                            COMMAND_ERROR_IS_FATAL ANY)
        endif()
    else()
        set(MPV_IMPLIB "${MPV_DIR}/libmpv.dll.a")
    endif()

    add_library(mpv::mpv SHARED IMPORTED)
    set_target_properties(mpv::mpv PROPERTIES
        IMPORTED_LOCATION "${MPV_DLL}"
        IMPORTED_IMPLIB "${MPV_IMPLIB}"
        INTERFACE_INCLUDE_DIRECTORIES "${MPV_INCLUDE_DIR}")
else()
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(MPV REQUIRED IMPORTED_TARGET mpv)
    add_library(mpv::mpv ALIAS PkgConfig::MPV)
endif()

# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------
qt_add_executable(BetterVideoPlayer src/main.cpp)

set(QML_FILES
    qml/Main.qml
    qml/Theme.qml
    qml/Icons.js
    qml/Icon.qml
    qml/IconButton.qml
    qml/TextAction.qml
    qml/AppLogo.qml
    qml/AppMenu.qml
    qml/AppMenuItem.qml
    qml/AppSlider.qml
    qml/AppSwitch.qml
    qml/RoundedImage.qml
    qml/EmptyNote.qml
    qml/VideoCard.qml
    qml/FolderCard.qml
    qml/PickRow.qml
    qml/Shelf.qml
    qml/ShelfHeader.qml
    qml/NavRail.qml
    qml/HomePage.qml
    qml/FolderPage.qml
    qml/WatchView.qml
    qml/ExternalPage.qml
    qml/SettingsPage.qml
)
set_source_files_properties(qml/Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
# Serve the files from the module root so they see each other without imports.
foreach(file IN LISTS QML_FILES)
    get_filename_component(name "${file}" NAME)
    set_source_files_properties("${file}" PROPERTIES QT_RESOURCE_ALIAS "${name}")
endforeach()

qt_add_qml_module(BetterVideoPlayer
    URI BetterVideoPlayer
    QML_FILES ${QML_FILES}
    SOURCES
        src/MpvItem.cpp src/MpvItem.h
        src/Thumbnailer.cpp src/Thumbnailer.h
        src/VideoLibrary.cpp src/VideoLibrary.h
        src/VideoModels.cpp src/VideoModels.h
        src/DriveWatcher.cpp src/DriveWatcher.h
)

target_include_directories(BetterVideoPlayer PRIVATE src)
if(WIN32)
    target_compile_definitions(BetterVideoPlayer PRIVATE NOMINMAX)
endif()
target_link_libraries(BetterVideoPlayer PRIVATE Qt6::Concurrent Qt6::OpenGL Qt6::Quick mpv::mpv)
target_compile_definitions(BetterVideoPlayer PRIVATE PROJECT_VERSION="${PROJECT_VERSION}")

set_target_properties(BetterVideoPlayer PROPERTIES
    WIN32_EXECUTABLE ON
    MACOSX_BUNDLE ON)

if(WIN32)
    # Make the build directory runnable: copy libmpv and deploy Qt next to the exe.
    find_program(WINDEPLOYQT_EXE windeployqt HINTS "${QT6_INSTALL_PREFIX}/${QT6_INSTALL_BINS}")
    add_custom_command(TARGET BetterVideoPlayer POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different "${MPV_DLL}" "$<TARGET_FILE_DIR:BetterVideoPlayer>"
        COMMAND "${WINDEPLOYQT_EXE}" --no-translations --no-opengl-sw --no-compiler-runtime
                --qmldir "${CMAKE_CURRENT_SOURCE_DIR}/qml" --verbose 0 "$<TARGET_FILE:BetterVideoPlayer>"
        VERBATIM)
endif()
