module Main exposing (main)

import Browser
import Browser.Navigation as Nav
import Http
import Model exposing (..)
import Ports
import Task
import Time
import Update
import Url exposing (Url)
import View


main : Program () Model Msg
main =
    Browser.application
        { init = init
        , view = View.view
        , update = Update.update
        , subscriptions = subscriptions
        , onUrlChange = UrlChanged
        , onUrlRequest = LinkClicked
        }


init : () -> Url -> Nav.Key -> ( Model, Cmd Msg )
init _ url key =
    let
        page =
            pageFromFragment url.fragment

        model =
            { key = key
            , page = page
            , messages = []
            , violations = []
            , schema = Nothing
            , plugin = ""
            , wsStatus = Connecting
            , error = Nothing
            , now = Time.millisToPosix 0
            }

        cmds =
            Cmd.batch
                [ Http.get
                    { url = "/api/schema"
                    , expect = Http.expectString SchemaResponse
                    }
                , Http.get
                    { url = "/api/plugin"
                    , expect = Http.expectString PluginResponse
                    }
                , Task.perform Tick Time.now
                ]
    in
    ( model, cmds )


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.batch
        [ Ports.wsMessage WsReceived
        , Ports.wsStatus WsStatusChanged
        , Ports.editorChanged EditorChanged
        , Time.every (60 * 1000) Tick
        ]
