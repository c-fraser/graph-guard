module Model exposing
    ( BoltMessage
    , BoltMsg(..)
    , Direction(..)
    , Model
    , Msg(..)
    , Page(..)
    , TimestampedViolation
    , Violation
    , WsEvent(..)
    , WsStatus(..)
    , boltMessageDecoder
    , pageFragment
    , pageFromFragment
    , violationDecoder
    , wsEventDecoder
    )

import Browser
import Browser.Navigation as Nav
import Dict exposing (Dict)
import Http
import Json.Decode as D
import Json.Decode.Pipeline exposing (optional, required)
import Time
import Url exposing (Url)


type Page
    = MessageStream
    | QueryLog
    | Violations
    | Schema
    | Plugins


type WsStatus
    = Connecting
    | Connected
    | Disconnected


type alias Model =
    { key : Nav.Key
    , page : Page
    , messages : List BoltMessage
    , violations : List TimestampedViolation
    , schema : Maybe String
    , plugin : String
    , wsStatus : WsStatus
    , error : Maybe String
    , now : Time.Posix
    , nextVerifyAt : Maybe Int
    }


type Msg
    = UrlChanged Url
    | LinkClicked Browser.UrlRequest
    | NavigateTo Page
    | WsReceived String
    | WsStatusChanged String
    | SchemaResponse (Result Http.Error String)
    | PluginResponse (Result Http.Error String)
    | SavePlugin
    | PluginSaved (Result Http.Error ())
    | EditorChanged String
    | CopyToClipboard String
    | Tick Time.Posix


type Direction
    = ReceivedFromClient
    | SentToClient
    | SentToGraph
    | ReceivedFromGraph


type BoltMsg
    = Run { query : String, parameters : Dict String D.Value }
    | Failure { metadata : Dict String D.Value }
    | Success { metadata : Dict String D.Value }
    | OtherBolt String


type alias BoltMessage =
    { session : String
    , direction : Direction
    , bolt : BoltMsg
    , address : String
    }


type alias Violation =
    { message : String
    , elementId : Maybe String
    , name : Maybe String
    , isNode : Bool
    }


type alias TimestampedViolation =
    { violation : Violation
    , lastSeen : Time.Posix
    }


type WsEvent
    = MessageEvent BoltMessage
    | ViolationEvent Violation
    | NextVerifyEvent Int


pageFromFragment : Maybe String -> Page
pageFromFragment fragment =
    case fragment of
        Just "queries" ->
            QueryLog

        Just "violations" ->
            Violations

        Just "schema" ->
            Schema

        Just "plugins" ->
            Plugins

        _ ->
            MessageStream


pageFragment : Page -> String
pageFragment page =
    case page of
        MessageStream ->
            "messages"

        QueryLog ->
            "queries"

        Violations ->
            "violations"

        Schema ->
            "schema"

        Plugins ->
            "plugins"


directionDecoder : D.Decoder Direction
directionDecoder =
    D.string
        |> D.andThen
            (\s ->
                case s of
                    "ReceivedFromClient" ->
                        D.succeed ReceivedFromClient

                    "SentToClient" ->
                        D.succeed SentToClient

                    "SentToGraph" ->
                        D.succeed SentToGraph

                    "ReceivedFromGraph" ->
                        D.succeed ReceivedFromGraph

                    _ ->
                        D.fail ("unknown direction: " ++ s)
            )


boltMsgDecoder : D.Decoder BoltMsg
boltMsgDecoder =
    D.field "type" D.string
        |> D.andThen
            (\t ->
                case t of
                    "Run" ->
                        D.map2
                            (\q p -> Run { query = q, parameters = p })
                            (D.field "query" D.string)
                            (D.field "parameters" (D.dict D.value))

                    "Failure" ->
                        D.map (\m -> Failure { metadata = m })
                            (D.field "metadata" (D.dict D.value))

                    "Success" ->
                        D.map (\m -> Success { metadata = m })
                            (D.field "metadata" (D.dict D.value))

                    _ ->
                        D.succeed (OtherBolt t)
            )


boltMessageDecoder : D.Decoder BoltMessage
boltMessageDecoder =
    D.succeed BoltMessage
        |> required "session" D.string
        |> required "direction" directionDecoder
        |> required "bolt" boltMsgDecoder
        |> required "address" D.string


violationDecoder : D.Decoder Violation
violationDecoder =
    D.succeed Violation
        |> required "message" D.string
        |> optional "elementId" (D.nullable D.string) Nothing
        |> optional "name" (D.nullable D.string) Nothing
        |> optional "isNode" D.bool True


wsEventDecoder : D.Decoder WsEvent
wsEventDecoder =
    D.field "type" D.string
        |> D.andThen
            (\t ->
                case t of
                    "message" ->
                        D.field "data" boltMessageDecoder
                            |> D.map MessageEvent

                    "violation" ->
                        D.field "data" violationDecoder
                            |> D.map ViolationEvent

                    "nextVerify" ->
                        D.at [ "data", "epochMs" ] D.int
                            |> D.map NextVerifyEvent

                    _ ->
                        D.fail ("unknown ws event type: " ++ t)
            )
