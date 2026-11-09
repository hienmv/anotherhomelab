/**
 * Accessible disclosure dropdown (button + list of links).
 * Markup: [data-menu] > button[data-menu-button][aria-controls] + [data-menu-list] a...
 * Enter/Space/click toggles, ArrowDown/ArrowUp move between links, Escape and outside clicks close.
 * Without JavaScript the list stays hidden and the button falls back to its href (/homelab/).
 */
export function initMenu(root: HTMLElement): () => void {
  const button = root.querySelector<HTMLElement>('[data-menu-button]')!
  const list = root.querySelector<HTMLElement>('[data-menu-list]')!
  const links = () => Array.from(list.querySelectorAll<HTMLAnchorElement>('a'))

  const isOpen = () => button.getAttribute('aria-expanded') === 'true'
  const open = () => {
    button.setAttribute('aria-expanded', 'true')
    list.hidden = false
  }
  const close = (focusButton = false) => {
    button.setAttribute('aria-expanded', 'false')
    list.hidden = true
    if (focusButton) button.focus()
  }
  const move = (delta: number) => {
    const items = links()
    if (items.length === 0) return
    const i = items.indexOf(document.activeElement as HTMLAnchorElement)
    items[(i + delta + items.length) % items.length].focus()
  }

  const onButtonClick = (e: Event) => {
    e.preventDefault()
    if (isOpen()) close()
    else open()
  }
  const onKeydown = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && isOpen()) {
      close(true)
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (!isOpen()) open()
      move(1)
    } else if (e.key === 'ArrowUp' && isOpen()) {
      e.preventDefault()
      move(-1)
    }
  }
  const onDocumentClick = (e: Event) => {
    if (isOpen() && !root.contains(e.target as Node)) close()
  }

  button.setAttribute('role', 'button')
  button.setAttribute('aria-expanded', 'false')
  list.hidden = true
  button.addEventListener('click', onButtonClick)
  root.addEventListener('keydown', onKeydown)
  document.addEventListener('click', onDocumentClick)
  return () => {
    button.removeEventListener('click', onButtonClick)
    root.removeEventListener('keydown', onKeydown)
    document.removeEventListener('click', onDocumentClick)
  }
}
