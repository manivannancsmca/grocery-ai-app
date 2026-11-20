/** "AB" from "Ab Cd Ef", "F" from "freshmart", "?" for nothing: the letters on an avatar. */
export function initialsOf(name: string | null | undefined): string {
  const words = (name ?? '').trim().split(/\s+/).filter((word) => /[\p{L}\p{N}]/u.test(word))
  const letters = words.slice(0, 2).map((word) => [...word].find((char) => /[\p{L}\p{N}]/u.test(char)) ?? '')
  return letters.join('').toUpperCase() || '?'
}
