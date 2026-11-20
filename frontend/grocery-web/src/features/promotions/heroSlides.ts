/**
 * The storefront hero's slideshow: one promotion per slide, each with its own colours and photos.
 *
 * Photos are the catalog's own (public/images/products, credits in CREDITS.md there), chosen without visible brands.
 * Each slide links to a top-level category, found by name in the live category tree, with a search as the fallback
 * when a catalog has no such category.
 *
 * Tailwind only sees class names written out in full, so every colour is a complete class string here.
 */
export interface HeroSlide {
  id: string
  /** Short label above the title, also the slide's name for screen readers ("Seasonal picks"). */
  eyebrow: string
  title: string
  text: string
  /** The top-level category "Shop now" opens (matched by name, ignoring case). */
  category: string
  /** Searched for instead when the category is not in the catalog. */
  query: string
  /** Three photos under /images/products: the large one first. */
  photos: [string, string, string]
  theme: {
    /** The slide's background gradient. Deep enough for the hero's white text (≥ 4.5:1 where the text sits). */
    gradient: string
    /** Two soft glows in the corners. */
    glows: [string, string]
    /** "Shop now" text colour on its white button. */
    accent: string
  }
}

const PHOTOS = '/images/products/'

export const HERO_SLIDES: readonly HeroSlide[] = [
  {
    id: 'staples',
    eyebrow: 'Kitchen staples',
    title: 'Rice, dals and ghee for every day',
    text: 'Stock up on the basics from stores near you.',
    category: 'Rice and Rice Products',
    query: 'rice',
    photos: ['basmati-rice.jpg', 'toor-dal.jpg', 'ghee.jpg'],
    theme: {
      gradient: 'from-emerald-600 via-emerald-700 to-teal-800',
      glows: ['bg-emerald-300/25', 'bg-amber-300/20'],
      accent: 'text-emerald-800',
    },
  },
  {
    id: 'fruits',
    eyebrow: 'Seasonal picks',
    title: 'Exotic fruits at their freshest',
    text: 'Juicy, ripe and picked for the season.',
    category: 'Fruits',
    query: 'fruits',
    photos: ['pineapple.jpg', 'pomegranate.jpg', 'mango.jpg'],
    theme: {
      gradient: 'from-rose-600 via-rose-700 to-pink-900',
      glows: ['bg-orange-300/30', 'bg-amber-200/20'],
      accent: 'text-rose-700',
    },
  },
  {
    id: 'baby-care',
    eyebrow: 'Baby care',
    title: 'Gentle care for little ones',
    text: 'Baby food, feeding bottles and everyday essentials.',
    category: 'Baby Care',
    query: 'baby',
    photos: ['baby-food.jpg', 'baby-feeding-bottles.jpg', 'baby-powder.jpg'],
    theme: {
      gradient: 'from-sky-700 via-blue-700 to-indigo-800',
      glows: ['bg-cyan-300/30', 'bg-pink-300/20'],
      accent: 'text-blue-700',
    },
  },
  {
    id: 'wellness',
    eyebrow: 'Healthy mornings',
    title: 'Every bite a taste of wellness',
    text: 'Oats, muesli and green tea to start the day right.',
    category: 'Breakfast and Cereals',
    query: 'oats',
    photos: ['muesli.jpg', 'oats.jpg', 'green-tea.jpg'],
    theme: {
      gradient: 'from-amber-700 via-orange-800 to-stone-900',
      glows: ['bg-yellow-300/25', 'bg-lime-300/15'],
      accent: 'text-amber-800',
    },
  },
  {
    id: 'home-care',
    eyebrow: 'Home care',
    title: 'A cleaner home, every day',
    text: 'Detergents, cleaners and more, delivered to your door.',
    category: 'Household Cleaning',
    query: 'cleaner',
    photos: ['laundry-detergent.jpg', 'glass-cleaner.jpg', 'floor-cleaner.jpg'],
    theme: {
      gradient: 'from-violet-600 via-purple-700 to-indigo-900',
      glows: ['bg-fuchsia-300/25', 'bg-sky-300/20'],
      accent: 'text-violet-700',
    },
  },
]

/** A slide photo's URL. */
export function slidePhoto(file: string): string {
  return PHOTOS + file
}
