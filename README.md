<p align="center"><a href="https://shoalapps.github.io/"><img src="https://raw.githubusercontent.com/ShoalApps/shoalapps.github.io/main/assets/readme/squill.svg" alt="Squill - a Shoal app, with the rest of the Shoal family" width="100%"></a></p>

Notes, links and reminders, as ordinary files on your phone. A Material You notes app for Android.

## Notes

- Notes are plain Markdown files in folders, so they're yours: back them up as a zip that any Markdown app can open
- Link notes with `[[double brackets]]`, follow them, see what links back, and watch it all join up in the graph
- Rename or move a note and every link to it is updated
- Keep-style cards: pins, colours, checklists you can tick, tags and search across everything
- Reading and writing views, a formatting bar, undo and redo, tables and code blocks
- A note for today, a trash you can restore from, and two panes on a tablet
- Import a folder of Markdown files, such as an Obsidian vault, keeping its subfolders
- Share text from any app to make a note of it
- Opens fast however many notes you have: 10,000 notes open in under half a second once indexed, on the test device
- Long-press the icon to jump straight to a new note, checklist, drawing or today's note
- Version history: recover an earlier version of a note, even after reopening or after a sync replaced it. Kept for 30 days, on this device, and encrypted if your notes are

## Private by choice

- **Squilly's tour** shows what each part of Squill does on first run, and again from Settings.
- **Where notes live**: private to Squill, or `Documents/Squill` as ordinary files other apps can open. Settings moves everything, and only removes the originals once it has all arrived.
- **Encrypt all notes**: AES-256-GCM under a password that's never stored. Squill asks for it when it opens and locks after five minutes away. File names stay visible, and backups aren't encrypted. The computer can encrypt its folder too, under its own password, in Devices. Reminders on the phone stay unencrypted, in private storage, so they can still ring while notes are locked.
- **Locked notes**: a separate encrypted vault with its own password, names included. It stays out of the list, search, graph and sync, and locks when you leave.
- **Forgetting a password loses those notes for good.** There's no recovery.
- **Graph linking**: drag one note onto another on the phone to link them, with Undo.
- **Open with Squill**: pictures open as paintings, and `.md`, `.docx`, `.pptx`, `.ora` and `.kra` files come in too. Formats Android can't decode, such as SVG and most TIFF, are refused with a message.

## Drawing

- Low-latency ink with pen, marker and highlighter, with pressure where the pen has it
- Palm rejection: once a pen has been used, fingers move around instead of drawing. A pen's eraser end and barrel button erase
- Lines, arrows, boxes, circles and text. Select, move, duplicate and delete. Undo and redo. Plain, lined, grid and dotted paper
- Drawings are ordinary SVG pictures you can open anywhere, can sit inside a note, and save as PNG

## Painting

A layered painting studio with Simple, Advanced and Focus modes, inspired by Krita.

- Layers with 26 blend modes, opacity, transparency lock and clipping, merged, duplicated and reordered as you like
- A stamp brush engine with 19 presets, from ink and pencil to watercolour, smudge and blur, and controls for flow, hardness, spacing, scatter, jitter, nib shape and stabilising, with pressure and tilt
- Selections by box, oval, lasso and magic wand, which limit painting, fills and filters
- Fill, gradients, shapes, text, transform, crop, and left-right, up-down and radial symmetry
- 17 filters, a colour picker with palette and recent colours, and a view you can pan, pinch and turn
- Saves as OpenRaster (.ora), the open layered format Krita can open, and as PNG and JPEG. Opens pictures, and a Krita file's flattened image

Not everything in Krita is here: there are no layer groups, filter masks, vector layers, animation timeline, perspective assistants, CMYK or high bit-depth colour, or plugins.

## Documents and slides

- Save any note as Word (.docx) or PDF, and open Word files as notes
- Bring in your Google Keep notes from a Google Takeout zip, with checklists, labels and photos. No Google account is linked, and importing twice adds nothing twice
- Bring in Evernote notebooks from .enex exports too, with formatting, checklists, tags and pictures
- Bring in Notion exports (Markdown & CSV), keeping pages in folders, with links between them working
- Presentations you can really edit on a phone: drag, resize and restyle text, boxes, circles, lines and pictures, with speaker notes
- Reveal things click by click when presenting, with undo and redo throughout
- Open and save PowerPoint (.pptx). What's on the slide travels; reveal steps stay in Squill, and imported animations and master slides don't come across

## Sync and your computer

- [Squill for Linux, Windows and Mac](https://github.com/ShoalApps/squill/releases/tag/desktop-latest) keeps your notes as ordinary files in a folder, so other Markdown apps can open it too
- Write and read notes there with a live preview, backlinks, the graph, tags and search. Draw there with a mouse or tablet, with an eraser, selection, and undo. Edit slide text, speaker notes and order too. Paint there too, with brushes, an eraser, layers and blend modes, though the advanced painting tools are on the phone, and a painting with anything the computer can't keep, such as Krita layer groups, opens read-only. The layout of slides is edited on your phone
- Pair your phone with a one-time code. They then sync directly over your own network, encrypted, with no account and no cloud
- If both change the same note before syncing, both versions are kept. Deletions go to the trash on the other device. Reminders sync too, and only the phone rings

## Reminders

- One-off, daily, weekday, weekly, monthly and yearly reminders that ring at the exact minute, even in Doze
- Done and Snooze buttons on the notification, with a snooze length you choose
- Overdue, Today, Tomorrow and Later at a glance, plus one-tap times when you jot something down
- A home-screen widget showing what's next
- Share text from any app to make a reminder of it
- Back up to a file of your choosing and restore it later
- Anything that came due while your phone was off rings when it starts up again
- Colours follow your wallpaper on Android 12+
- No account, no cloud and no tracking: Squill only ever connects to a computer you've paired it with

Part of [Shoal](https://github.com/ShoalApps). Free software under the GPL-3.0.

## Support

Shoal is free, open source and has no ads. If you'd like to help it keep going, you can [support Shoal on Liberapay](https://liberapay.com/ShoalApps).
