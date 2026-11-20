import { useState } from 'react'
import { cn } from '../../shared/utils/cn'
import { ProductImage } from './ProductImage'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} ProductGalleryProps
 * @property {readonly string[]} images
 * @property {string} productName
 * @property {ReactNode} [badge] Overlay in the photo's top-left corner (e.g. the discount ribbon).
 * @property {boolean} [muted] Greys the photo out (sold out).
 */

/**
 * Large photo plus a row of thumbnails (buttons) to switch between the product's images.
 * @param {ProductGalleryProps} props
 */
export function ProductGallery({ images, productName, badge, muted = false }) {
  const [selected, setSelected] = useState(0)
  const current = images[selected] ?? images[0] ?? null

  return (
    <div className="space-y-3">
      <div className="relative rounded-3xl border border-slate-200/80 bg-white p-2.5 shadow-sm shadow-slate-900/[0.03] sm:p-3">
        <div className="overflow-hidden rounded-2xl bg-gradient-to-br from-slate-50 to-emerald-50/40">
          <ProductImage
            src={current}
            alt={images.length > 1 ? `${productName}, image ${selected + 1} of ${images.length}` : productName}
            className={cn('aspect-square w-full', muted && 'opacity-60 grayscale')}
            iconClassName="size-20"
          />
        </div>
        {badge && <div className="absolute top-5 left-5 sm:top-6 sm:left-6">{badge}</div>}
      </div>
      {images.length > 1 && (
        <ul className="-mx-1 flex gap-2.5 overflow-x-auto px-1 py-1" aria-label="Product images">
          {images.map((src, index) => (
            <li key={`${src}-${index}`} className="shrink-0">
              <button
                type="button"
                onClick={() => setSelected(index)}
                aria-label={`Show image ${index + 1} of ${images.length}`}
                aria-pressed={index === selected}
                className={cn(
                  'block overflow-hidden rounded-xl bg-white p-0.5 ring-2 transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
                  index === selected
                    ? 'ring-emerald-500'
                    : 'opacity-80 ring-slate-200 hover:opacity-100 hover:ring-slate-300',
                )}
              >
                <ProductImage src={src} alt="" className="size-16 rounded-[5px] sm:size-20" iconClassName="size-6" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
