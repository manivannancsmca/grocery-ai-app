import { useState } from 'react'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { type CategoryTint, type IconKind, iconKind } from './categoryStyle'

/**
 * Picture-free category art: a colourful icon picked from the category's name by keyword, on a pastel tint that
 * changes from tile to tile. A category's own photo (`imageUrl`) wins when it has one and it loads.
 */

const CATEGORY_ICONS: Record<IconKind, IconName> = {
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

/** The keyword-picked icon for a category name. */
export function CategoryIcon({ name, className }: { name: string; className?: string }) {
  return <Icon name={CATEGORY_ICONS[iconKind(name)]} className={className} />
}

interface CategoryArtProps {
  name: string
  imageUrl: string | null | undefined
  tint: CategoryTint
  className?: string
}

/** Round badge with the category's photo, or its keyword icon when there is no photo (or it fails to load). */
export function CategoryArt(props: CategoryArtProps) {
  // Remount per source so a failed photo does not hide the next one.
  return <CategoryArtInner key={props.imageUrl ?? ''} {...props} />
}

function CategoryArtInner({ name, imageUrl, tint, className }: CategoryArtProps) {
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
