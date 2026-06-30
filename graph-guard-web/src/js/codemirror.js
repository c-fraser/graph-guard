import { EditorView, basicSetup } from 'codemirror'
import { keymap, lineNumbers, drawSelection, highlightActiveLine } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { search, searchKeymap } from '@codemirror/search'
import { StreamLanguage, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { kotlin } from '@codemirror/legacy-modes/mode/clike'
import { cypher } from '@codemirror/legacy-modes/mode/cypher'
import { oneDark } from '@codemirror/theme-one-dark'

let editorView = null
let schemaView = null

export function destroyAll() {
  if (editorView) {
    editorView.destroy()
    editorView = null
  }
  if (schemaView) {
    schemaView.destroy()
    schemaView = null
  }
}

export function initEditor(content) {
  destroyAll()
  const mount = document.querySelector('.codemirror-mount')
  if (!mount) return
  editorView = new EditorView({
    doc: content,
    extensions: [basicSetup, StreamLanguage.define(kotlin), oneDark],
    parent: mount,
  })
}

export function setEditorContent(content) {
  if (!editorView) return
  const current = editorView.state.doc.toString()
  if (current === content) return
  editorView.dispatch({
    changes: { from: 0, to: editorView.state.doc.length, insert: content },
  })
}

export function getEditorContent(callback) {
  if (!editorView) {
    callback('')
    return
  }
  callback(editorView.state.doc.toString())
}

export function initSchemaView(content) {
  destroyAll()
  const mount = document.querySelector('.schema-codemirror-mount')
  if (!mount) return
  schemaView = new EditorView({
    doc: content,
    extensions: [
      lineNumbers(),
      drawSelection(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      highlightActiveLine(),
      StreamLanguage.define(cypher),
      oneDark,
      EditorView.editable.of(false),
      EditorState.readOnly.of(true),
      search({ top: true }),
      keymap.of(searchKeymap),
    ],
    parent: mount,
  })
}
