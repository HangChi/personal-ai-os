export interface InlineCitation {
  index: number
  documentId?: string
  documentTitle: string
  filename: string
  chunkIndex: number
  content: string
  url?: string | null
  source?: { version?: string | null; scope?: string | null } | null
}

export function safeCitationUrl(raw?: string | null): URL | undefined {
  if (!raw) return
  try {
    const url = new URL(raw)
    if ((url.protocol === 'https:' || url.protocol === 'http:') && !url.username && !url.password) return url
  } catch {
    // An invalid source URL remains previewable as plain text.
  }
}

// Run on sanitized Markdown DOM so code, links and HTML attributes stay untouched.
export function decorateCitations(root: HTMLElement, sources: ReadonlyMap<number, InlineCitation>): number {
  const nodes = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const textNodes: Text[] = []
  let verifiedNumbers = 0
  while (nodes.nextNode()) {
    const node = nodes.currentNode as Text
    if (!node.parentElement?.closest('a, button, code, pre, script, style, textarea, svg')) textNodes.push(node)
  }
  for (const node of textNodes) {
    const matches = [...node.data.matchAll(/\[(\d+)\]/g)]
    if (!matches.length) continue
    const fragment = document.createDocumentFragment()
    let offset = 0
    for (const match of matches) {
      fragment.append(node.data.slice(offset, match.index))
      offset = match.index + match[0].length
      if (!sources.has(Number(match[1]))) {
        const unavailable = document.createElement('span')
        unavailable.className = 'md-citation-unavailable'
        unavailable.textContent = `${match[0]} 无对应来源`
        fragment.append(unavailable)
        continue
      }
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'md-citation'
      button.dataset.citation = match[1]
      button.textContent = match[0]
      button.setAttribute('aria-label', `查看引用 ${match[1]}`)
      button.setAttribute('aria-expanded', 'false')
      fragment.append(button)
      verifiedNumbers++
    }
    fragment.append(node.data.slice(offset))
    node.replaceWith(fragment)
  }
  return verifiedNumbers
}
