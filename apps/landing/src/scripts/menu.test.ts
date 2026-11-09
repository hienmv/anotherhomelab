import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { initMenu } from './menu'

let cleanup: () => void

function setup() {
  document.body.innerHTML = `
    <div data-menu>
      <a href="/homelab/" data-menu-button aria-controls="list">Homelab</a>
      <ul id="list" data-menu-list>
        <li><a href="https://a.example">A</a></li>
        <li><a href="https://b.example">B</a></li>
      </ul>
    </div>
    <p id="outside">outside</p>`
  cleanup = initMenu(document.querySelector('[data-menu]')!)
  return {
    button: document.querySelector<HTMLElement>('[data-menu-button]')!,
    list: document.querySelector<HTMLElement>('[data-menu-list]')!,
    links: Array.from(document.querySelectorAll<HTMLAnchorElement>('[data-menu-list] a')),
  }
}
const key = (el: Element, k: string) => el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }))

describe('homelab dropdown', () => {
  beforeEach(() => (document.body.innerHTML = ''))
  afterEach(() => cleanup?.())

  it('starts closed and toggles on click', () => {
    const { button, list } = setup()
    expect(list.hidden).toBe(true)
    expect(button.getAttribute('aria-expanded')).toBe('false')
    button.click()
    expect(list.hidden).toBe(false)
    expect(button.getAttribute('aria-expanded')).toBe('true')
    button.click()
    expect(list.hidden).toBe(true)
  })

  it('does not navigate to the fallback page when JS is on', () => {
    const { button } = setup()
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true })
    button.dispatchEvent(ev)
    expect(ev.defaultPrevented).toBe(true)
  })

  it('ArrowDown opens and focuses links, wrapping around', () => {
    const { button, links } = setup()
    button.focus()
    key(button, 'ArrowDown')
    expect(document.activeElement).toBe(links[0])
    key(links[0], 'ArrowDown')
    expect(document.activeElement).toBe(links[1])
    key(links[1], 'ArrowDown')
    expect(document.activeElement).toBe(links[0])
    key(links[0], 'ArrowUp')
    expect(document.activeElement).toBe(links[1])
  })

  it('Escape closes and returns focus to the button', () => {
    const { button, list, links } = setup()
    button.click()
    links[0].focus()
    key(links[0], 'Escape')
    expect(list.hidden).toBe(true)
    expect(document.activeElement).toBe(button)
  })

  it('closes on outside click', () => {
    const { button, list } = setup()
    button.click()
    document.getElementById('outside')!.click()
    expect(list.hidden).toBe(true)
  })
})
