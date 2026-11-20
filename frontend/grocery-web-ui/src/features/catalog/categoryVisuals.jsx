import { useState } from 'react'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { iconKind } from './categoryStyle'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./categoryStyle').CategoryTint} CategoryTint */
/** @typedef {import('./categoryStyle').IconKind} IconKind */

/**
 * Picture-free category art: a colourful icon picked from the category's name by keyword, on a pastel tint that
 * changes from tile to tile. A category's own photo (`imageUrl`) wins when it has one and it loads.
 */

/** @type {Record<IconKind, IconName>} */
const CATEGORY_ICONS = {
  produce: 'apple',
  dairy: 'milk',
  bakery: 'bread',
  grains: 'wheat',
  pulses: 'lentils',
  flour: 'flourSack',
  snacks: 'cookie',
  beverages: 'juice',
  meat: 'fish',
  eggs: 'egg',
  spices: 'chilli',
  oil: 'oilBottle',
  frozen: 'iceCream',
  household: 'sprayBottle',
  care: 'pumpBottle',
  basket: 'basket',
}

/**
 * The keyword-picked icon for a category name.
 * @param {{ name: string, className?: string }} props
 */
export function CategoryIcon({ name, className }) {
  return <Icon name={CATEGORY_ICONS[iconKind(name)]} className={className} />
}

/**
 * @typedef {object} CategoryArtProps
 * @property {string} name
 * @property {string | null | undefined} imageUrl
 * @property {CategoryTint} tint
 * @property {string} [className]
 */

/**
 * Round badge with the category's photo, or its keyword icon when there is no photo (or it fails to load).
 * @param {CategoryArtProps} props
 */
export function CategoryArt(props) {
  // Remount per source so a failed photo does not hide the next one.
  return <CategoryArtInner key={props.imageUrl ?? ''} {...props} />
}

/** @param {CategoryArtProps} props */
function CategoryArtInner({ name, imageUrl, tint, className }) {
  const [failed, setFailed] = useState(false)
  const showPhoto = Boolean(imageUrl) && !failed
  return (
    <span
      aria-hidden="true"
      className={cn(
        'relative flex items-center justify-center overflow-hidden rounded-full shadow-sm ring-1 transition-transform duration-300 group-hover:scale-105',
        tint.badge,
        className,
      )}
    >
      {showPhoto ? (
        <img
          src={imageUrl ?? undefined}
          alt=""
          loading="lazy"
          decoding="async"
          onError={() => setFailed(true)}
          className="size-full object-cover"
        />
      ) : (
        <CategoryIcon name={name} className="size-1/2" />
      )}
    </span>
  )
}
