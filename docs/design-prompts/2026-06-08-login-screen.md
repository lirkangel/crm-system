# claude.ai/design prompt — Login screen (F201)

Paste the block below into claude.ai/design. It is written so the generated
component is **presentational only** and drops onto the existing `useLoginForm`
hook via a thin container (Claude wires that part locally).

---

Design a **desktop login screen** for an on-premise hotel CRM / PMS used by
front-desk and back-office staff in Vietnam. This is a Tauri desktop app window
(around 1280×800), not a mobile or marketing page — design for a fixed desktop
window, calm and professional, enterprise software feel. Avoid generic
"AI startup" gradients and hero fluff.

**Tech constraints (must follow):**

- React 18 + TypeScript, Tailwind CSS **v4**, shadcn/ui (style: **new-york**,
  base color: **neutral**, CSS variables), icons from **lucide-react**.
- Use shadcn primitives where natural: `Card`, `Input`, `Label`, `Button`.
- Build a single **presentational** component. All text is passed in as props
  (the app handles translation/i18n) — do **not** hardcode user-facing copy
  beyond sensible defaults. Light + dark mode via the existing CSS variables
  (`bg-background`, `text-foreground`, `text-muted-foreground`, etc.).

**Component contract (use exactly this props shape):**

```ts
interface LoginScreenProps {
  // already-translated strings (the app translates i18n keys before passing)
  title: string;            // e.g. "Đăng nhập"
  subtitle: string;         // e.g. "Đăng nhập vào hệ thống CRM"
  usernameLabel: string;
  usernamePlaceholder: string;
  passwordLabel: string;
  passwordPlaceholder: string;
  submitLabel: string;      // idle button text
  submittingLabel: string;  // button text while submitting

  values: { username: string; password: string };
  // per-field error message (already translated), undefined when valid
  errors: { username?: string; password?: string };
  // form-level error (already translated), e.g. bad credentials, or null
  formError: string | null;
  submitting: boolean;

  onFieldChange: (field: "username" | "password", value: string) => void;
  onSubmit: () => void; // call on form submit; container runs validation
  // optional slot rendered in a top corner for the language switcher
  languageSlot?: React.ReactNode;
}
```

**Layout & behavior:**

- Centered card on a neutral full-window background. App brand/title at the top
  of the card (a small lucide icon + product name is fine).
- A `languageSlot` rendered top-right of the window (a VI/EN switcher lives
  there — just leave room and render the slot).
- Form: username field, then password field (type=password), then a full-width
  primary submit button.
- **Field errors:** show `errors.username` / `errors.password` inline beneath
  each field in the destructive color; set `aria-invalid` on the input.
- **Form error:** when `formError` is set, show it prominently above or below
  the button (e.g. a small destructive alert row with an alert icon).
- **Submitting:** while `submitting` is true, disable inputs + button and show
  `submittingLabel` with a spinner (lucide `Loader2`, animate-spin).
- Submitting via Enter key should trigger `onSubmit` (wrap fields in a `<form>`
  with `onSubmit` calling `preventDefault` then `props.onSubmit`).
- Accessible: real `<label>`s tied to inputs, button is `type="submit"`.

**Tone:** trustworthy, dense-but-breathable, suitable for daily staff use.
Vietnamese is the default language, so make sure labels of ~25 characters and
Vietnamese diacritics fit without clipping.

Deliver the single `LoginScreen.tsx` component plus the `buttonVariants`/shadcn
imports it relies on. No routing, no API calls, no state — purely driven by props.
