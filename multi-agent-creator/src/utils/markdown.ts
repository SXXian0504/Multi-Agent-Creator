/**
 * Markdown 工具函数
 */
import { marked } from 'marked'

/**
 * 将 Markdown 转换为 HTML
 * @param markdown Markdown 内容
 */
export const markdownToHtml = (markdown: string): string => {
  return marked(markdown) as string
}

const escapeRegExp = (value: string): string => {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const safeAlt = (value: string | undefined): string => {
  return (value || '配图').replace(/[\[\]\n\r]/g, ' ')
}

const escapeHtmlAttribute = (value: string | undefined): string => {
  return (value || '')
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

const tagImageBySrc = (html: string, imageUrl: string, className: string): string => {
  return html.replace(/<img\b[^>]*>/g, (tag) => {
    if (!tag.includes(imageUrl) || tag.includes(className)) {
      return tag
    }
    if (/\bclass="/.test(tag)) {
      return tag.replace(/\bclass="([^"]*)"/, `class="$1 ${className}"`)
    }
    return tag.replace('<img', `<img class="${className}"`)
  })
}

/**
 * Render article markdown and resolve image placeholders with generated images.
 */
export const articleMarkdownToHtml = (
  markdown: string | undefined,
  images: API.ImageItem[] | undefined,
): string => {
  let content = markdown || ''
  if (images && images.length > 0) {
    const coverImage = images.find((image) => image.position === 1 && image.url)
    if (coverImage?.url && !content.includes(coverImage.url)) {
      content = `<p class="article-cover-paragraph"><img class="article-cover-image" src="${escapeHtmlAttribute(coverImage.url)}" alt="${escapeHtmlAttribute(safeAlt(coverImage.description))}" /></p>\n\n${content}`
    }
    images
      .filter((image) => image.position !== 1 && image.placeholderId && image.url)
      .forEach((image) => {
        const placeholder = image.placeholderId as string
        const imageMarkdown = `![${safeAlt(image.description)}](${image.url})`
        content = content.replace(new RegExp(escapeRegExp(placeholder), 'g'), imageMarkdown)
      })
  }
  let html = markdownToHtml(content)
  const coverImage = images?.find((image) => image.position === 1 && image.url)
  if (coverImage?.url) {
    html = tagImageBySrc(html, coverImage.url, 'article-cover-image')
  }
  return html
}
