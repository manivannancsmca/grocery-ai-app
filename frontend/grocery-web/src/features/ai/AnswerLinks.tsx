import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import type { PageLink } from './api'
import { ANSWER_LINK_ARROW, ANSWER_LINK_BUTTON } from './linkStyles'

interface AnswerLinksProps {
  links: readonly PageLink[]
  /** Following a link (e.g. closes the chat panel so the page is visible). */
  onNavigate?: () => void
}

/** The pages an answer is about ("View my cart", "Track order GA-…"), as buttons under it. */
export function AnswerLinks({ links, onNavigate }: AnswerLinksProps) {
  if (links.length === 0) return null
  return (
    <nav aria-label="Related pages">
      <ul className="flex flex-wrap gap-2">
        {links.map((link) => (
          <li key={link.path}>
            <Link to={link.path} onClick={onNavigate} className={cn(ANSWER_LINK_BUTTON, 'px-3 py-1.5 text-xs')}>
              <span className="truncate">{link.label}</span>
              <Icon name="arrowRight" className={ANSWER_LINK_ARROW} />
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}
