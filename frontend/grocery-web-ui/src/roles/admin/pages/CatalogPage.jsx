import { useSearchParams } from 'react-router'
import { CategoriesTab } from '../../../features/admin-catalog/CategoriesTab'
import { ProductsTab } from '../../../features/admin-catalog/ProductsTab'
import { SearchIndexTab } from '../../../features/admin-catalog/SearchIndexTab'
import { PageHeader, Tabs } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'

/** @typedef {import('../../../shared/components/ui').TabItem} TabItem */

/** @typedef {'categories' | 'products' | 'search'} CatalogTab */

// The icons are decorative: each tab's accessible name stays its text.
/** @type {readonly TabItem<CatalogTab>[]} */
const TABS = [
  {
    id: 'categories',
    label: (
      <>
        <Icon name="grid" className="size-4" />
        Categories
      </>
    ),
  },
  {
    id: 'products',
    label: (
      <>
        <Icon name="box" className="size-4" />
        Products
      </>
    ),
  },
  {
    id: 'search',
    label: (
      <>
        <Icon name="search" className="size-4" />
        Search index
      </>
    ),
  },
]

/**
 * @param {string | null} value
 * @returns {value is CatalogTab}
 */
function isCatalogTab(value) {
  return value === 'categories' || value === 'products' || value === 'search'
}

/** `/admin/catalog` — category tree, product oversight and search index status (`?tab=` in the URL). */
export function CatalogPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tabParam = searchParams.get('tab')
  const tab = isCatalogTab(tabParam) ? tabParam : 'categories'

  return (
    <div>
      <PageHeader
        icon="basket"
        title="Catalog"
        description="Categories, every vendor's products and the health of the search index."
      />
      <Tabs
        label="Catalog sections"
        tabs={TABS}
        value={tab}
        onChange={(next) => setSearchParams({ tab: next }, { replace: true })}
      >
        {tab === 'categories' && <CategoriesTab />}
        {tab === 'products' && <ProductsTab />}
        {tab === 'search' && <SearchIndexTab />}
      </Tabs>
    </div>
  )
}
