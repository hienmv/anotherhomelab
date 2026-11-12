// Site values, injected at build time by astro.config.mjs (vite.define); tests: vitest.config.ts.
declare const __SITE_DOMAIN__: string
declare const __SITE_TIMEZONE__: string

export const DOMAIN = __SITE_DOMAIN__
export const TIMEZONE = __SITE_TIMEZONE__

/** https://<sub>.<domain><path>, or the apex when sub is ''. */
export const siteUrl = (sub = '', path = '') => `https://${sub ? `${sub}.` : ''}${DOMAIN}${path}`
