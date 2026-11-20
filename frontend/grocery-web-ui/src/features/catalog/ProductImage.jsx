import { useState } from 'react'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'

/**
 * @typedef {object} ProductImageProps
 * @property {string | null | undefined} src
 * @property {string} alt Alt text; pass '' for decorative thumbnails next to the product name.
 * @property {string} [className]
 * @property {string} [iconClassName] Placeholder icon size.
 */

/**
 * Product / dish photo with a neat placeholder when there is none or it fails to load.
 * @param {ProductImageProps} props
 */
export function ProductImage(props) {
  // Remount per source so a failed image does not hide the next one.
  return <ProductImageInner key={props.src ?? ''} {...props} />
}

/** @param {ProductImageProps} props */
function ProductImageInner({ src, alt, className, iconClassName = 'size-8' }) {
  const [failed, setFailed] = useState(false)

  if (!src || failed) {
    return (
      <div
        role={alt ? 'img' : undefined}
        aria-label={alt ? `${alt} (no photo)` : undefined}
        aria-hidden={alt ? undefined : true}
        className={cn('flex items-center justify-center bg-gradient-to-br from-emerald-50 to-slate-100', className)}
      >
        <Icon name="leaf" className={cn(iconClassName, 'opacity-60')} />
      </div>
    )
  }

  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      decoding="async"
      onError={() => setFailed(true)}
      className={cn('object-cover', className)}
    />
  )
}
