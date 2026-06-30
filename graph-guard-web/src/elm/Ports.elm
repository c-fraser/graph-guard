port module Ports exposing (..)


port initEditor : String -> Cmd msg


port setEditorContent : String -> Cmd msg


port getEditorContent : () -> Cmd msg


port initSchemaView : String -> Cmd msg


port destroyEditors : () -> Cmd msg


port copyToClipboard : String -> Cmd msg


port consoleLog : String -> Cmd msg


port wsMessage : (String -> msg) -> Sub msg


port wsStatus : (String -> msg) -> Sub msg


port editorChanged : (String -> msg) -> Sub msg
