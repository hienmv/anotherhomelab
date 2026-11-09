import { defineCollection, z } from 'astro:content'
import { glob } from 'astro/loaders'

// One folder per note: src/content/notes/<slug>/index.md, images next to it.
const notes = defineCollection({
  loader: glob({
    pattern: '*/index.md',
    base: './src/content/notes',
    generateId: ({ entry }) => entry.replace(/\/index\.md$/, ''),
  }),
  schema: z.object({
    title: z.string(),
    date: z.coerce.date(),
    category: z.string(),
    lang: z.enum(['vi', 'en']).default('en'),
    description: z.string().optional(),
    /** Path on the old Jekyll site (hienmv.github.io); nginx redirects it here. */
    oldUrl: z.string().optional(),
  }),
})

export const collections = { notes }
