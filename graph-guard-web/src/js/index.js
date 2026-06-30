import { Elm } from '../elm/Main.elm'
import {
  initEditor,
  setEditorContent,
  getEditorContent,
  initSchemaView,
  destroyAll,
} from './codemirror.js'

const app = Elm.Main.init({ node: document.getElementById('root') })

let ws

function connect() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  ws = new WebSocket(`${protocol}//${location.host}/ws`)
  app.ports.wsStatus.send('connecting')
  ws.onopen = () => app.ports.wsStatus.send('connected')
  ws.onmessage = (e) => {
    console.debug('[ws] data:', e.data)
    app.ports.wsMessage.send(e.data)
  }
  ws.onclose = () => {
    app.ports.wsStatus.send('disconnected')
    setTimeout(connect, 3000)
  }
  ws.onerror = () => {
    app.ports.wsStatus.send('disconnected')
  }
}

connect()

app.ports.copyToClipboard.subscribe((text) => navigator.clipboard.writeText(text))

app.ports.consoleLog.subscribe((msg) => console.log('[elm]', msg))

app.ports.initEditor.subscribe((content) => {
  // defer so Elm has painted the codemirror-mount node
  requestAnimationFrame(() => {
    initEditor(content)
  })
})

app.ports.setEditorContent.subscribe((content) => setEditorContent(content))

app.ports.getEditorContent.subscribe(() => {
  getEditorContent((text) => app.ports.editorChanged.send(text))
})

app.ports.initSchemaView.subscribe((content) => {
  requestAnimationFrame(() => {
    initSchemaView(content)
  })
})

app.ports.destroyEditors.subscribe(() => destroyAll())
