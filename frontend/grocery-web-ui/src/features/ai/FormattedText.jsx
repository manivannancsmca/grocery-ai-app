import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { ANSWER_LINK_ARROW, ANSWER_LINK_BUTTON } from './linkStyles'

/** @typedef {import('react').ReactNode} ReactNode */

/** `- item`, `* item`, `• item` → a bullet line. */
const BULLET = /^\s*[-*•]\s+(.*)$/

/** The inline segments: `**bold**` and `[label](/path)`. */
const SEGMENT = /(\*\*[^*\n]+\*\*|\[[^\]\n]{1,80}\]\([^)\s]{1,300}\))/g
const LINK = /^\[([^\]\n]{1,80})\]\(([^)\s]{1,300})\)$/

/**
 * Which link paths may become clickable, and what following one does (e.g. close the chat panel).
 * @typedef {object} InlineLinks
 * @property {(path: string) => boolean} allow
 * @property {() => void} [onNavigate]
 */

/**
 * `**bold**` → <strong>; `[label](/path)` → a button to that app page when `links` allows the path, else just its
 * label (never another website); everything else stays text (React escapes it).
 * @param {string} text
 * @param {string} keyPrefix
 * @param {InlineLinks | undefined} links
 * @returns {ReactNode[]}
 */
function inline(text, keyPrefix, links) {
  return text
    .split(SEGMENT)
    .filter((part) => part.length > 0)
    .map((part, index) => {
      const key = `${keyPrefix}-${index}`
      const link = LINK.exec(part)
      if (link) {
        const [, label = '', path = ''] = link
        return links?.allow(path) ? (
          <Link
            key={key}
            to={path}
            onClick={links.onNavigate}
            className={cn(ANSWER_LINK_BUTTON, 'mx-0.5 my-0.5 px-2.5 py-1 align-middle text-xs')}
          >
            <span className="truncate">{label}</span>
            <Icon name="arrowRight" className={ANSWER_LINK_ARROW} />
          </Link>
        ) : (
          label
        )
      }
      return part.length > 4 && part.startsWith('**') && part.endsWith('**') ? (
        <strong key={key} className="font-semibold text-slate-900">
          {part.slice(2, -2)}
        </strong>
      ) : (
        part
      )
    })
}

/** @typedef {{ kind: 'paragraph', lines: string[] } | { kind: 'list', items: string[] }} Block */

/**
 * Groups lines into paragraphs (split by blank lines) and bullet lists.
 * @param {string} text
 * @returns {Block[]}
 */
function toBlocks(text) {
  const blocks = []
  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    const bullet = BULLET.exec(line)
    const last = blocks.at(-1)
    if (bullet) {
      const item = bullet[1] ?? ''
      if (last?.kind === 'list') last.items.push(item)
      else blocks.push({ kind: 'list', items: [item] })
    } else if (line.trim() === '') {
      if (last && !(last.kind === 'paragraph' && last.lines.length === 0)) blocks.push({ kind: 'paragraph', lines: [] })
    } else if (last?.kind === 'paragraph') {
      last.lines.push(line)
    } else {
      blocks.push({ kind: 'paragraph', lines: [line] })
    }
  }
  return blocks.filter((block) => (block.kind === 'paragraph' ? block.lines.length > 0 : block.items.length > 0))
}

/**
 * The assistant's answer as plain text — never as HTML. Line breaks are kept; the only formatting is `**bold**`,
 * bullet lines and links to the app's own pages (`links`), built here as React elements, so whatever the model
 * writes cannot inject markup.
 * @param {{ text: string, links?: InlineLinks }} props
 */
export function FormattedText({ text, links }) {
  return (
    <div className="space-y-2 text-sm leading-relaxed break-words text-slate-800">
      {toBlocks(text).map((block, blockIndex) =>
        block.kind === 'list' ? (
          <ul key={blockIndex} className="list-disc space-y-0.5 pl-5">
            {block.items.map((item, itemIndex) => (
              <li key={itemIndex}>{inline(item, `${blockIndex}-${itemIndex}`, links)}</li>
            ))}
          </ul>
        ) : (
          <p key={blockIndex} className="whitespace-pre-wrap">
            {block.lines.flatMap((line, lineIndex) => [
              ...(lineIndex > 0 ? [<br key={`br-${lineIndex}`} />] : []),
              ...inline(line, `${blockIndex}-${lineIndex}`, links),
            ])}
          </p>
        ),
      )}
    </div>
  )
}
