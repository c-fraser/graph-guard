module Update exposing (update)

import Browser
import Browser.Navigation as Nav
import Http
import Json.Decode as JD
import Model exposing (..)
import Ports
import Process
import Task
import Url


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        UrlChanged url ->
            let
                page =
                    pageFromFragment url.fragment

                cmd =
                    if page == Plugins then
                        if model.pluginLoading then
                            Cmd.none

                        else
                            Ports.initEditor model.plugin

                    else if page == Schema then
                        Maybe.withDefault Cmd.none (Maybe.map Ports.initSchemaView model.schema)

                    else if model.page == Plugins || model.page == Schema then
                        Ports.destroyEditors ()

                    else
                        Cmd.none
            in
            ( { model | page = page }, cmd )

        LinkClicked req ->
            case req of
                Browser.Internal url ->
                    ( model, Nav.pushUrl model.key (Url.toString url) )

                Browser.External href ->
                    ( model, Nav.load href )

        NavigateTo page ->
            let
                fragment =
                    pageFragment page

                navigateCmd =
                    Nav.pushUrl model.key ("#" ++ fragment)

                extraCmd =
                    if page == Plugins then
                        if model.pluginLoading then
                            Cmd.none

                        else
                            Ports.initEditor model.plugin

                    else if page == Schema then
                        Maybe.withDefault Cmd.none (Maybe.map Ports.initSchemaView model.schema)

                    else if model.page == Plugins || model.page == Schema then
                        Ports.destroyEditors ()

                    else
                        Cmd.none
            in
            ( { model | page = page }, Cmd.batch [ navigateCmd, extraCmd ] )

        WsReceived raw ->
            case JD.decodeString wsEventDecoder raw of
                Ok (MessageEvent bm) ->
                    ( { model | messages = (bm :: model.messages) |> List.take 2048 }, Cmd.none )

                Ok (ViolationEvent v) ->
                    let
                        updated =
                            case List.filter (\tv -> tv.violation == v) model.violations of
                                _ :: _ ->
                                    { violation = v, lastSeen = model.now }
                                        :: List.filter (\tv -> tv.violation /= v) model.violations

                                [] ->
                                    ({ violation = v, lastSeen = model.now } :: model.violations)
                                        |> List.take 2048
                    in
                    ( { model | violations = updated }, Cmd.none )

                Ok (NextVerifyEvent ms) ->
                    ( { model | nextVerifyAt = Just ms }, Cmd.none )

                Err err ->
                    ( model, Ports.consoleLog ("[ws] decode error: " ++ JD.errorToString err) )

        WsStatusChanged s ->
            let
                wsStatus =
                    case s of
                        "connected" ->
                            Connected

                        "disconnected" ->
                            Disconnected

                        _ ->
                            Connecting
            in
            ( { model | wsStatus = wsStatus }, Cmd.none )

        SchemaResponse result ->
            case result of
                Ok schema ->
                    ( { model | schema = Just schema }
                    , if model.page == Schema then
                        Ports.initSchemaView schema

                      else
                        Cmd.none
                    )

                Err (Http.BadStatus 204) ->
                    ( { model | schema = Nothing }, Cmd.none )

                Err err ->
                    ( { model | schema = Nothing }, Ports.consoleLog ("[schema] error: " ++ httpErrorToString err) )

        PluginResponse result ->
            case result of
                Ok plugin ->
                    ( { model | plugin = plugin, pluginLoading = False }
                    , if model.page == Plugins then
                        if model.pluginLoading then
                            Ports.initEditor plugin

                        else
                            Ports.setEditorContent plugin

                      else
                        Cmd.none
                    )

                Err err ->
                    ( { model | plugin = "", pluginLoading = False }, Ports.consoleLog ("[plugin] error: " ++ httpErrorToString err) )

        SavePlugin ->
            ( { model | saveStatus = Saving }, Ports.getEditorContent () )

        EditorChanged content ->
            ( model
            , Http.request
                { method = "POST"
                , headers = []
                , url = "/api/plugin"
                , body = Http.stringBody "text/plain" content
                , expect = Http.expectWhatever PluginSaved
                , timeout = Nothing
                , tracker = Nothing
                }
            )

        PluginSaved result ->
            case result of
                Ok () ->
                    ( { model | saveStatus = Saved }
                    , Cmd.batch
                        [ Http.get { url = "/api/plugin", expect = Http.expectString PluginResponse }
                        , Process.sleep 3000 |> Task.perform (\_ -> ClearSaveStatus)
                        ]
                    )

                Err err ->
                    ( { model | saveStatus = SaveFailed }, Ports.consoleLog ("[plugin save] error: " ++ httpErrorToString err) )

        ClearSaveStatus ->
            ( { model | saveStatus = Idle }, Cmd.none )

        CopyToClipboard text_ ->
            ( model, Ports.copyToClipboard text_ )

        Tick posix ->
            ( { model | now = posix }, Cmd.none )


httpErrorToString : Http.Error -> String
httpErrorToString err =
    case err of
        Http.BadUrl url ->
            "bad url: " ++ url

        Http.Timeout ->
            "timeout"

        Http.NetworkError ->
            "network error"

        Http.BadStatus code ->
            "bad status: " ++ String.fromInt code

        Http.BadBody body ->
            "bad body: " ++ body
