module View exposing (view)

import Browser
import Css
import Css.Global
import Dict
import Html.Styled exposing (..)
import Html.Styled.Attributes exposing (alt, attribute, class, css, href, rel, src, target)
import Html.Styled.Events exposing (onClick)
import Json.Encode as JE
import Model exposing (..)
import Tailwind.Theme as Theme
import Tailwind.Utilities as Tw
import Time


view : Model -> Browser.Document Msg
view model =
    { title = "graph-guard"
    , body =
        [ toUnstyled
            (div []
                [ Css.Global.global Tw.globalStyles
                , appShell model
                ]
            )
        ]
    }


appShell : Model -> Html Msg
appShell model =
    div
        [ css
            [ Tw.bg_gradient_to_br
            , Tw.from_color Theme.slate_900
            , Tw.to_color Theme.slate_800
            , Tw.h_screen
            , Tw.flex
            , Tw.flex_col
            , Tw.p_2
            ]
        ]
        [ div
            [ css
                [ Tw.flex_1
                , Tw.min_h_0
                , Tw.bg_color Theme.slate_900
                , Tw.rounded_xl
                , Tw.shadow_2xl
                , Tw.overflow_hidden
                , Tw.flex
                , Tw.flex_row
                ]
            ]
            [ sidebar model.page
            , div [ css [ Tw.flex_1, Tw.flex, Tw.flex_col, Tw.overflow_hidden ] ]
                [ case model.error of
                    Just err ->
                        errorDisplay err

                    Nothing ->
                        pageContent model
                ]
            ]
        ]


sidebar : Page -> Html Msg
sidebar current =
    div
        [ css
            [ Tw.w_60
            , Tw.bg_gradient_to_b
            , Tw.from_color Theme.gray_900
            , Tw.to_color Theme.gray_800
            , Tw.py_5
            , Tw.flex
            , Tw.flex_col
            ]
        ]
        [ div
            [ css
                [ Tw.px_5
                , Tw.pb_5
                , Tw.flex
                , Tw.justify_center
                , Tw.items_center
                , Tw.border_b
                , Tw.border_b_color Theme.gray_700
                , Tw.mb_2_dot_5
                ]
            ]
            [ img [ src "/graph-guard-dark.png", alt "graph-guard logo", css [ Tw.w_28 ] ] [] ]
        , div []
            (List.map (sidebarItem current)
                [ MessageStream, QueryLog, Violations, Schema, Plugins ]
            )
        ]


sidebarItem : Page -> Page -> Html Msg
sidebarItem current page =
    let
        isActive =
            page == current

        activeStyles =
            if isActive then
                [ Tw.bg_color Theme.blue_500
                , Tw.bg_opacity_20
                , Tw.text_color Theme.gray_100
                , Tw.border_l_color Theme.blue_500
                ]

            else
                [ Tw.border_l_color Theme.transparent ]
    in
    div
        [ css
            ([ Tw.px_6
             , Tw.py_4
             , Tw.cursor_pointer
             , Tw.flex
             , Tw.items_center
             , Tw.gap_3
             , Tw.text_sm
             , Tw.font_medium
             , Tw.border_l_4
             , Tw.transition_all
             , Tw.duration_200
             , Tw.text_color Theme.gray_400
             , Css.hover
                [ Tw.bg_color Theme.white
                , Tw.bg_opacity_10
                , Tw.text_color Theme.gray_100
                ]
             ]
                ++ activeStyles
            )
        , onClick (NavigateTo page)
        ]
        [ i [ css [ Tw.text_lg, Tw.w_6, Tw.text_center, Tw.inline_flex, Tw.items_center, Tw.justify_center ], class (pageIcon page) ] []
        , text (pageLabel page)
        ]


pageIcon : Page -> String
pageIcon page =
    case page of
        MessageStream ->
            "fas fa-stream"

        QueryLog ->
            "fas fa-list"

        Violations ->
            "fas fa-shield-alt"

        Schema ->
            "fas fa-file-code"

        Plugins ->
            "fas fa-plug"


pageLabel : Page -> String
pageLabel page =
    case page of
        MessageStream ->
            "Messages"

        QueryLog ->
            "Queries"

        Violations ->
            "Violations"

        Schema ->
            "Schema"

        Plugins ->
            "Plugins"


errorDisplay : String -> Html msg
errorDisplay message =
    div
        [ css [ Tw.p_8, Tw.text_center, Tw.text_color Theme.red_500 ] ]
        [ i [ class "fas fa-exclamation-triangle" ] []
        , span [] [ text (" " ++ message) ]
        ]


pageContent : Model -> Html Msg
pageContent model =
    case model.page of
        MessageStream ->
            messageStreamPage model.messages

        QueryLog ->
            queryLogPage model.messages

        Violations ->
            violationsPage model.violations model.nextVerifyAt model.now

        Schema ->
            schemaPage model.schema

        Plugins ->
            pluginsPage


pageHeader : String -> Maybe (Html msg) -> Maybe (Html msg) -> Html msg
pageHeader title subtitle action =
    header
        [ css
            [ Tw.bg_gradient_to_br
            , Tw.from_color Theme.blue_900
            , Tw.to_color Theme.blue_700
            , Tw.text_color Theme.gray_100
            , Tw.px_8
            , Tw.py_8
            , Tw.flex
            , Tw.justify_between
            , Tw.items_center
            ]
        ]
        [ case subtitle of
            Just sub ->
                div [ css [ Tw.flex, Tw.flex_col, Tw.gap_1 ] ]
                    [ h1 [ css [ Tw.text_3xl, Tw.font_semibold, Tw.m_0 ] ] [ text title ]
                    , sub
                    ]

            Nothing ->
                h1 [ css [ Tw.text_3xl, Tw.font_semibold, Tw.m_0 ] ] [ text title ]
        , case action of
            Just act ->
                act

            Nothing ->
                text ""
        ]


flowContainer : List (Html msg) -> Html msg
flowContainer =
    div [ css [ Tw.flex_1, Tw.overflow_hidden, Tw.flex, Tw.flex_col ] ]


flowList : List (Html msg) -> Html msg
flowList children =
    div
        [ css
            [ Tw.p_5
            , Tw.overflow_y_auto
            , Tw.overflow_x_hidden
            , Tw.scroll_smooth
            , Tw.flex_1
            ]
        ]
        children


emptyState : String -> Html msg
emptyState msg =
    div [ css [ Tw.p_8, Tw.text_center, Tw.text_color Theme.gray_400 ] ]
        [ p [] [ text msg ] ]


waitingEmptyState : String -> Html msg
waitingEmptyState msg =
    div [ css [ Tw.p_8, Tw.text_center, Tw.text_color Theme.gray_400 ] ]
        [ div
            [ css
                [ Tw.animate_spin
                , Tw.rounded_full
                , Tw.h_8
                , Tw.w_8
                , Tw.border_b_2
                , Tw.border_b_color Theme.gray_400
                , Tw.mx_auto
                , Tw.mb_4
                ]
            ]
            []
        , p [] [ text msg ]
        ]


successEmptyState : String -> Html msg
successEmptyState msg =
    div [ css [ Tw.p_8, Tw.text_center ] ]
        [ i [ class "fas fa-check-circle", css [ Tw.text_color Theme.green_500, Tw.text_4xl, Tw.block, Tw.mb_2 ] ] []
        , p [ css [ Tw.text_color Theme.green_400 ] ] [ text msg ]
        ]


messageStreamPage : List BoltMessage -> Html msg
messageStreamPage messages =
    flowContainer
        [ pageHeader "Bolt Messages" Nothing Nothing
        , flowList
            (if List.isEmpty messages then
                [ waitingEmptyState "No Bolt messages intercepted yet..." ]

             else
                List.map messageCard messages
            )
        ]


directionStyles : Direction -> List Css.Style
directionStyles direction =
    case direction of
        ReceivedFromClient ->
            [ Tw.bg_color Theme.orange_400
            , Tw.bg_opacity_20
            , Tw.border_l_4
            , Tw.border_l_color Theme.orange_400
            ]

        SentToClient ->
            [ Tw.bg_color Theme.blue_500
            , Tw.bg_opacity_25
            , Tw.border_l_4
            , Tw.border_l_color Theme.blue_400
            ]

        SentToGraph ->
            [ Tw.bg_color Theme.orange_400
            , Tw.bg_opacity_25
            , Tw.border_l_4
            , Tw.border_l_color Theme.amber_400
            ]

        ReceivedFromGraph ->
            [ Tw.bg_color Theme.blue_500
            , Tw.bg_opacity_20
            , Tw.border_l_4
            , Tw.border_l_color Theme.blue_500
            ]


boltMsgSummary : BoltMsg -> String
boltMsgSummary bolt =
    case bolt of
        Run r ->
            "Run(" ++ r.query ++ ")"

        Failure _ ->
            "Failure"

        Success _ ->
            "Success"

        OtherBolt s ->
            s


messageCard : BoltMessage -> Html msg
messageCard message =
    div
        [ css
            ([ Tw.mb_2
             , Tw.rounded_md
             , Tw.px_4
             , Tw.py_2_dot_5
             , Tw.overflow_hidden
             , Tw.shadow_sm
             , Tw.flex
             , Tw.items_center
             , Tw.gap_2_dot_5
             , Tw.text_sm
             , Tw.leading_relaxed
             , Css.hover [ Tw.neg_translate_y_px, Tw.shadow_md ]
             , Tw.transition_all
             , Tw.duration_200
             ]
                ++ directionStyles message.direction
            )
        ]
        [ span [ css [ Tw.flex, Tw.items_center, Tw.gap_2, Tw.text_xs, Tw.flex_shrink_0 ] ]
            (messageFlow message)
        , span [ css [ Tw.font_mono, Tw.text_xs, Tw.text_color Theme.gray_200, Tw.break_words, Tw.flex_1 ] ]
            [ text (boltMsgSummary message.bolt) ]
        ]


messageFlow : BoltMessage -> List (Html msg)
messageFlow message =
    let
        clientIcon =
            i [ css [ Tw.inline_flex, Tw.items_center, Tw.justify_center, Tw.flex_shrink_0, Tw.text_lg, Tw.text_color Theme.gray_200 ], class "fas fa-laptop" ] []

        guardIcon =
            img [ css [ Tw.w_8, Tw.h_8, Tw.object_contain ], src "/graph-guard.png", alt "graph-guard" ] []

        dbIcon =
            i [ css [ Tw.inline_flex, Tw.items_center, Tw.justify_center, Tw.flex_shrink_0, Tw.text_lg, Tw.text_color Theme.blue_400 ], class "fas fa-database" ] []

        arrow dir =
            i [ css [ Tw.text_color Theme.gray_200, Tw.text_base ], class ("fas fa-arrow-" ++ dir) ] []

        addr =
            span [ css [ Tw.font_mono, Tw.text_xs, Tw.text_color Theme.gray_400 ] ] [ text message.address ]
    in
    case message.direction of
        ReceivedFromClient ->
            [ clientIcon, addr, arrow "right", guardIcon ]

        SentToClient ->
            [ clientIcon, addr, arrow "left", guardIcon ]

        SentToGraph ->
            [ guardIcon, arrow "right", dbIcon, addr ]

        ReceivedFromGraph ->
            [ guardIcon, arrow "left", dbIcon, addr ]


type alias QueryEntry =
    { session : String
    , cypher : String
    , parameters : List ( String, JE.Value )
    , response : Maybe BoltMsg
    , clientAddress : String
    }


getQueries : List BoltMessage -> List QueryEntry
getQueries messages =
    let
        -- messages list is newest-first; reverse to process in arrival order
        inOrder =
            List.reverse messages

        grouped =
            List.foldl
                (\msg acc ->
                    case List.filter (\( s, _ ) -> s == msg.session) acc of
                        [] ->
                            acc ++ [ ( msg.session, [ msg ] ) ]

                        _ ->
                            List.map
                                (\( s, ms ) ->
                                    if s == msg.session then
                                        ( s, ms ++ [ msg ] )

                                    else
                                        ( s, ms )
                                )
                                acc
                )
                []
                inOrder
    in
    List.concatMap (extractSessionQueries << Tuple.second) grouped


extractSessionQueries : List BoltMessage -> List QueryEntry
extractSessionQueries sessionMsgs =
    List.indexedMap
        (\idx msg ->
            case ( msg.direction, msg.bolt ) of
                ( ReceivedFromClient, Run r ) ->
                    let
                        response =
                            List.drop (idx + 1) sessionMsgs
                                |> List.foldl
                                    (\m acc ->
                                        case acc of
                                            Just _ ->
                                                acc

                                            Nothing ->
                                                case ( m.direction, m.bolt ) of
                                                    ( SentToClient, Success _ ) ->
                                                        Just m.bolt

                                                    ( SentToClient, Failure _ ) ->
                                                        Just m.bolt

                                                    _ ->
                                                        Nothing
                                    )
                                    Nothing
                    in
                    Just
                        { session = msg.session
                        , cypher = r.query
                        , parameters = Dict.toList r.parameters
                        , response = response
                        , clientAddress = msg.address
                        }

                _ ->
                    Nothing
        )
        sessionMsgs
        |> List.filterMap identity


queryLogPage : List BoltMessage -> Html msg
queryLogPage messages =
    let
        queries =
            getQueries messages
    in
    flowContainer
        [ pageHeader "Query Log" Nothing Nothing
        , flowList
            (if List.isEmpty queries then
                [ waitingEmptyState "No queries intercepted yet..." ]

             else
                List.map queryLogCard queries
            )
        ]


queryLogCard : QueryEntry -> Html msg
queryLogCard query =
    let
        ( statusColor, statusIcon, leftBorderColor ) =
            case query.response of
                Just (Success _) ->
                    ( Theme.green_500, "fas fa-check-circle", Theme.green_500 )

                Just (Failure _) ->
                    ( Theme.red_500, "fas fa-times-circle", Theme.red_500 )

                _ ->
                    ( Theme.gray_400, "fas fa-clock", Theme.gray_400 )
    in
    div
        [ css
            [ Tw.mb_4
            , Tw.rounded_lg
            , Tw.overflow_hidden
            , Tw.shadow_sm
            , Tw.border
            , Tw.border_color Theme.gray_700
            , Tw.bg_color Theme.gray_900
            , Tw.border_l_4
            , Tw.border_l_color leftBorderColor
            , Css.hover [ Tw.neg_translate_y_0_dot_5, Tw.shadow_md ]
            , Tw.transition_all
            , Tw.duration_200
            ]
        ]
        [ div
            [ css
                [ Tw.px_4
                , Tw.py_3
                , Tw.bg_color Theme.gray_800
                , Tw.border_b
                , Tw.border_b_color Theme.gray_700
                , Tw.flex
                , Tw.items_center
                , Tw.gap_3
                , Tw.text_xs
                ]
            ]
            [ i [ css [ Tw.text_xl, Tw.w_6, Tw.text_center, Tw.flex_shrink_0, Tw.text_color statusColor ], class statusIcon ] []
            , span [ css [ Tw.flex, Tw.items_center, Tw.gap_2, Tw.flex_shrink_0 ] ]
                [ i [ css [ Tw.text_color Theme.gray_200 ], class "fas fa-laptop" ] []
                , span [ css [ Tw.text_color Theme.gray_400, Tw.font_mono, Tw.text_xs ] ] [ text query.clientAddress ]
                ]
            , span [ css [ Tw.text_color Theme.gray_400, Tw.font_mono, Tw.text_xs, Tw.ml_auto, Tw.flex, Tw.items_center, Tw.gap_1_dot_5 ] ]
                [ i [ class "fas fa-circle-nodes" ] []
                , text (" " ++ query.session)
                ]
            ]
        , div [ css [ Tw.p_4 ] ]
            [ pre
                [ css
                    [ Tw.m_0
                    , Tw.p_3
                    , Tw.bg_color Theme.gray_800
                    , Tw.rounded
                    , Tw.font_mono
                    , Tw.text_sm
                    , Tw.text_color Theme.gray_200
                    , Tw.overflow_x_auto
                    , Tw.break_words
                    , Tw.leading_relaxed
                    ]
                ]
                [ code [] [ text query.cypher ] ]
            , case query.response of
                Just (Failure f) ->
                    let
                        errMsg =
                            Dict.get "message" f.metadata
                                |> Maybe.map (JE.encode 0)
                                |> Maybe.withDefault "Unknown"
                    in
                    details [ css [ Tw.mt_3 ] ]
                        [ summary
                            [ css
                                [ Tw.cursor_pointer
                                , Tw.px_3
                                , Tw.py_2_dot_5
                                , Tw.bg_color Theme.red_500
                                , Tw.bg_opacity_20
                                , Tw.border
                                , Tw.border_color Theme.red_500
                                , Tw.border_opacity_30
                                , Tw.rounded
                                , Tw.font_semibold
                                , Tw.text_sm
                                , Tw.text_color Theme.red_500
                                , Tw.select_none
                                , Tw.flex
                                , Tw.items_center
                                , Tw.gap_2
                                , Css.hover [ Tw.bg_color Theme.red_500, Tw.bg_opacity_25 ]
                                ]
                            ]
                            [ i [ class "fas fa-exclamation-triangle" ] []
                            , text (" Failure: " ++ errMsg)
                            ]
                        , pre
                            [ css
                                [ Tw.m_0
                                , Tw.mt_2
                                , Tw.p_3
                                , Tw.bg_color Theme.red_500
                                , Tw.bg_opacity_10
                                , Tw.border
                                , Tw.border_color Theme.red_500
                                , Tw.border_opacity_30
                                , Tw.border_t_0
                                , Tw.rounded_b_lg
                                , Tw.font_mono
                                , Tw.text_xs
                                , Tw.text_color Theme.gray_400
                                , Tw.overflow_x_auto
                                ]
                            ]
                            [ text
                                (Dict.toList f.metadata
                                    |> List.map (\( k, v ) -> k ++ ": " ++ JE.encode 0 v)
                                    |> String.join "\n"
                                )
                            ]
                        ]

                _ ->
                    text ""
            , if List.isEmpty query.parameters then
                text ""

              else
                details [ css [ Tw.mt_3 ] ]
                    [ summary
                        [ css
                            [ Tw.cursor_pointer
                            , Tw.px_3
                            , Tw.py_2
                            , Tw.bg_color Theme.gray_800
                            , Tw.rounded
                            , Tw.font_semibold
                            , Tw.text_xs
                            , Tw.text_color Theme.gray_200
                            , Tw.select_none
                            , Tw.flex
                            , Tw.items_center
                            , Tw.gap_2
                            , Css.hover [ Tw.bg_color Theme.gray_700 ]
                            ]
                        ]
                        [ i [ css [ Tw.text_color Theme.blue_500 ], class "fas fa-code" ] []
                        , text " Parameters"
                        ]
                    , pre
                        [ css
                            [ Tw.m_0
                            , Tw.mt_2
                            , Tw.p_3
                            , Tw.bg_color Theme.gray_800
                            , Tw.rounded_b_lg
                            , Tw.font_mono
                            , Tw.text_xs
                            , Tw.text_color Theme.gray_400
                            , Tw.overflow_x_auto
                            ]
                        ]
                        [ text
                            (query.parameters
                                |> List.map (\( k, v ) -> k ++ ": " ++ JE.encode 0 v)
                                |> String.join "\n"
                            )
                        ]
                    ]
            ]
        ]


violationsPage : List TimestampedViolation -> Maybe Int -> Time.Posix -> Html Msg
violationsPage violations nextVerifyAt now =
    flowContainer
        [ pageHeader "Schema Violations"
            (Just
                (div [ css [ Tw.flex, Tw.flex_col, Tw.gap_1 ] ]
                    [ p
                        [ css [ Tw.text_sm, Tw.m_0, Tw.font_normal, Tw.text_color Theme.gray_100, Tw.text_opacity_70 ] ]
                        [ text "Violations reported by the "
                        , a
                            [ href "https://c-fraser.github.io/graph-guard/api/graph-guard-verify/io.github.cfraser.graphguard.verify/-verifier/index.html"
                            , target "_blank"
                            , rel "noreferrer"
                            , css [ Tw.text_color Theme.blue_300, Tw.text_opacity_90 ]
                            ]
                            [ text "Verifier" ]
                        ]
                    , nextVerifyDisplay nextVerifyAt now
                    ]
                )
            )
            Nothing
        , flowList
            (if List.isEmpty violations then
                [ successEmptyState "No violations found..." ]

             else
                List.map (violationCard now) violations
            )
        ]


nextVerifyDisplay : Maybe Int -> Time.Posix -> Html msg
nextVerifyDisplay nextVerifyAt now =
    case nextVerifyAt of
        Nothing ->
            text ""

        Just ms ->
            let
                diff =
                    ms - Time.posixToMillis now

                label =
                    if diff <= 0 then
                        "verification running..."

                    else
                        let
                            m =
                                diff // 60000
                        in
                        "next verification in "
                            ++ (if m > 0 then
                                    String.fromInt m ++ "m"

                                else
                                    "< 1m"
                               )
            in
            span
                [ css [ Tw.text_xs, Tw.text_color Theme.gray_100, Tw.text_opacity_50 ] ]
                [ text label ]


violationCard : Time.Posix -> TimestampedViolation -> Html Msg
violationCard now tv =
    let
        v =
            tv.violation

        diffSeconds =
            (Time.posixToMillis now - Time.posixToMillis tv.lastSeen) // 1000

        timeLabel =
            if diffSeconds < 60 then
                "just now"

            else if diffSeconds < 3600 then
                String.fromInt (diffSeconds // 60) ++ " minutes ago"

            else
                String.fromInt (diffSeconds // 3600) ++ " hours ago"

        quotedLabel =
            v.name |> Maybe.map (\n -> "`" ++ String.replace "`" "``" n ++ "`")

        matchQuery =
            v.elementId
                |> Maybe.map
                    (\id ->
                        if v.isNode then
                            let
                                label =
                                    quotedLabel |> Maybe.map (\l -> ":" ++ l) |> Maybe.withDefault ""
                            in
                            "MATCH (n" ++ label ++ ") WHERE elementId(n) = '" ++ id ++ "' RETURN n"

                        else
                            let
                                type_ =
                                    quotedLabel |> Maybe.map (\l -> ":" ++ l) |> Maybe.withDefault ""
                            in
                            "MATCH ()-[r" ++ type_ ++ "]->() WHERE elementId(r) = '" ++ id ++ "' RETURN r"
                    )
    in
    div
        [ css
            [ Tw.mb_2
            , Tw.rounded_md
            , Tw.px_4
            , Tw.py_3
            , Tw.bg_color Theme.red_500
            , Tw.bg_opacity_10
            , Tw.border
            , Tw.border_color Theme.red_500
            , Tw.border_opacity_25
            , Tw.border_l_4
            , Tw.border_l_color Theme.red_500
            , Tw.shadow_sm
            , Tw.flex
            , Tw.items_start
            , Tw.gap_3
            , Css.hover [ Tw.neg_translate_y_px, Tw.shadow_md ]
            , Tw.transition_all
            , Tw.duration_200
            ]
        ]
        [ i [ css [ Tw.text_color Theme.red_500, Tw.text_base, Tw.flex_shrink_0, Tw.mt_0_dot_5 ], class "fas fa-exclamation-triangle" ] []
        , div [ css [ Tw.flex, Tw.flex_col, Tw.gap_1, Tw.min_w_0 ] ]
            [ span [ css [ Tw.text_sm, Tw.text_color Theme.gray_200, Tw.break_words ] ] [ text v.message ]
            , case v.elementId of
                Just id ->
                    span [ css [ Tw.font_mono, Tw.text_xs, Tw.text_color Theme.gray_400, Tw.flex, Tw.items_center, Tw.gap_1 ] ]
                        ([ i [ class "fas fa-fingerprint" ] []
                         , text (" Element ID: " ++ id)
                         ]
                            ++ (case matchQuery of
                                    Just q ->
                                        [ button
                                            [ css
                                                [ Tw.bg_color Theme.transparent
                                                , Tw.border_none
                                                , Tw.cursor_pointer
                                                , Tw.text_color Theme.gray_400
                                                , Tw.text_xs
                                                , Tw.px_1_dot_5
                                                , Tw.py_0_dot_5
                                                , Tw.rounded
                                                , Tw.inline_flex
                                                , Tw.items_center
                                                , Css.hover
                                                    [ Tw.text_color Theme.blue_500
                                                    , Tw.bg_color Theme.blue_500
                                                    , Tw.bg_opacity_10
                                                    ]
                                                , Tw.transition_all
                                                , Tw.duration_200
                                                ]
                                            , attribute "title" "Copy MATCH query to clipboard"
                                            , onClick (CopyToClipboard q)
                                            ]
                                            [ i [ class "fas fa-copy" ] [] ]
                                        ]

                                    Nothing ->
                                        []
                               )
                        )

                Nothing ->
                    text ""
            , span [ css [ Tw.font_mono, Tw.text_xs, Tw.text_color Theme.gray_400, Tw.flex, Tw.items_center, Tw.gap_1, Tw.mt_1 ] ]
                [ i [ class "fas fa-clock" ] []
                , text (" Last seen: " ++ timeLabel)
                ]
            ]
        ]


schemaPage : Maybe String -> Html msg
schemaPage schema =
    flowContainer
        [ pageHeader "Graph Schema" Nothing Nothing
        , div [ css [ Tw.flex_1, Tw.overflow_hidden, Tw.flex, Tw.flex_col ] ]
            (case schema of
                Just _ ->
                    [ div [ css [ Tw.p_5, Tw.flex, Tw.flex_col, Tw.flex_1, Tw.overflow_hidden ] ]
                        [ div
                            [ class "schema-codemirror-mount"
                            , css
                                [ Tw.flex_1
                                , Tw.min_h_0
                                , Tw.overflow_hidden
                                , Tw.border
                                , Tw.border_color Theme.gray_700
                                , Tw.rounded_lg
                                ]
                            ]
                            []
                        ]
                    ]

                Nothing ->
                    [ emptyState "No schema configured..." ]
            )
        ]


pluginsPage : Html Msg
pluginsPage =
    flowContainer
        [ pageHeader "Plugin Editor"
            Nothing
            (Just
                (div
                    [ css
                        [ Tw.bg_color Theme.green_500
                        , Tw.bg_opacity_20
                        , Tw.border_2
                        , Tw.border_color Theme.green_500
                        , Tw.border_opacity_30
                        , Tw.px_4
                        , Tw.py_2_dot_5
                        , Tw.rounded_lg
                        , Tw.cursor_pointer
                        , Tw.text_color Theme.gray_100
                        , Tw.inline_flex
                        , Tw.items_center
                        , Tw.gap_2
                        , Tw.text_base
                        , Css.hover
                            [ Tw.bg_color Theme.green_500
                            , Tw.bg_opacity_40
                            , Tw.border_color Theme.green_500
                            , Tw.border_opacity_50
                            ]
                        , Tw.transition_all
                        , Tw.duration_200
                        ]
                    , attribute "title" "Save plugin"
                    , onClick SavePlugin
                    ]
                    [ i [ class "fas fa-save" ] []
                    , span [] [ text "Save" ]
                    ]
                )
            )
        , div [ css [ Tw.flex_1, Tw.overflow_hidden, Tw.flex, Tw.flex_col ] ]
            [ div [ css [ Tw.p_5, Tw.flex, Tw.flex_col, Tw.flex_1, Tw.overflow_hidden ] ]
                [ div
                    [ class "codemirror-mount"
                    , css
                        [ Tw.flex_1
                        , Tw.min_h_0
                        , Tw.overflow_hidden
                        , Tw.border
                        , Tw.border_color Theme.gray_700
                        , Tw.rounded_lg
                        ]
                    ]
                    []
                ]
            ]
        ]
