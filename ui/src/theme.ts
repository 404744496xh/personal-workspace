import { ref } from 'vue'

type Scheme = 'light' | 'dark'

export const scheme = ref<Scheme>('light')

const PREFERENCE_KEY = 'workspace:theme'

function storedPreference(): Scheme | 'system' {
  try {
    const value = localStorage.getItem(PREFERENCE_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

function explicitScheme(): Scheme | null {
  for (const element of [document.documentElement, document.body]) {
    const attr = element.getAttribute('data-color-scheme')
    if (attr === 'dark' || attr === 'light') return attr
    if (element.classList.contains('color-scheme-dark')) return 'dark'
    if (element.classList.contains('color-scheme-light')) return 'light'
  }
  return null
}

function effectiveScheme(): Scheme {
  const preference = storedPreference()
  if (preference !== 'system') return preference
  return explicitScheme()
    ?? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
}

function apply(): void {
  const root = document.getElementById('workspace-app')
  if (!root) return
  const next = effectiveScheme()
  scheme.value = next
  if (root.getAttribute('data-ws-theme') !== next) {
    root.setAttribute('data-ws-theme', next)
  }
}

export function toggleScheme(): void {
  const next: Scheme = scheme.value === 'dark' ? 'light' : 'dark'
  try {
    localStorage.setItem(PREFERENCE_KEY, next)
  } catch {
    // Private mode may reject writes; the in-memory switch still applies.
  }
  apply()
}

export function watchColorScheme(): void {
  apply()
  const media = window.matchMedia('(prefers-color-scheme: dark)')
  media.addEventListener('change', apply)
  const observer = new MutationObserver(apply)
  for (const element of [document.documentElement, document.body]) {
    observer.observe(element, {
      attributes: true,
      attributeFilter: ['data-color-scheme', 'class']
    })
  }
}
