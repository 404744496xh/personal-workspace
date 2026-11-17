import { reactive } from 'vue'

export interface ContextMenuItem {
  label: string
  onSelect?: () => void
  disabled?: boolean
  danger?: boolean
  divider?: boolean
}

export interface ContextMenuState {
  open: boolean
  x: number
  y: number
  items: ContextMenuItem[]
}

export function useContextMenu() {
  const menu = reactive<ContextMenuState>({
    open: false,
    x: 0,
    y: 0,
    items: []
  })

  function open(event: MouseEvent, items: ContextMenuItem[]): void {
    event.preventDefault()
    event.stopPropagation()
    menu.items = items
    menu.x = event.clientX
    menu.y = event.clientY
    menu.open = true
  }

  function close(): void {
    menu.open = false
  }

  return { menu, open, close }
}
