# Notipal

Notipal is a toolkit plugin for **Supernote tablets**.

It adds a floating toolbar to Notes and Documents. From there you can take document screenshots, insert images, keep things on eight clipboards, restyle handwriting, send content to other devices with LocalSend, and ask an AI on your phone about what you wrote. It also works together with [Mosaic](https://github.com/Not-Just-Supernote/sn-plugin-mosaic), a whiteboard plugin for Supernote.

Everything happens on your local network. Notipal has no cloud service of its own.

## Quick Start

### Get the App

Download the latest `.snplg` from [GitHub Releases](https://github.com/Not-Just-Supernote/sn-plugin-notipal/releases), or [build it from source](#build-from-source).

### What You Need

| For | You need |
| --- | --- |
| The toolbar, screenshots, clipboards, Ink Palette | A Supernote with plugin support |
| Sending and receiving | Wi-Fi, and [LocalSend](https://localsend.org) on the other device |
| AI Relay | An Android phone running RikkaHub on the same Wi-Fi |
| Mosaic features | The [Mosaic](https://github.com/Not-Just-Supernote/sn-plugin-mosaic) plugin |

### First Launch

Notipal has three entry points:

- **Note toolbar**: the Notipal button opens or closes the toolbar in Notes.
- **Document toolbar**: the same button opens or closes the toolbar in Documents.
- **Document text menu**: after you select text in a document, **Send to other devices** sends that text over LocalSend.

The first time you open it, Notipal asks for file read, write, and delete access, and for network access. Choose **Always allow** for every one. Notipal runs in the background, so a one-time grant stops working.

## Features

### Floating Toolbar

The toolbar has a column of tools and a narrow side column with the clipboards and a few utility buttons. Drag it with your finger to move it anywhere on the screen. The pen can tap its buttons, and it won't leave ink on the page underneath.

Which tools appear depends on where you are:

| Where | Tools | Side column |
| --- | --- | --- |
| Note | Insert Image, Doc Screenshot, Smart Lasso, Ink Palette, AI Relay | Clipboards, Text Receive, Note to Mosaic, Collapse |
| Document | Doc Screenshot, Smart Lasso, Ink Palette, AI Relay | Clipboards, Go to Note, Collapse |
| Mosaic | Insert Image, Doc Screenshot, Smart Lasso, AI Relay | Clipboards, Text Receive, Ink Palette, Collapse |

**Collapse** docks the toolbar to the nearest screen edge as a small handle. Tap the handle, or swipe it toward the middle of the screen, to bring the toolbar back. Long press the handle to close Notipal completely.

**Go to Note** switches from a document straight back to the Notes app.

### Clipboards

Notipal has eight clipboard slots. They are numbered in the side column, and a filled slot shows white on black. When not all eight fit, swipe up or down on the numbers to see the rest.

| Action | With a lasso selection | Without a selection |
| --- | --- | --- |
| Tap a slot | Save the selection into it, asking before it overwrites | Paste it |
| Long press an empty slot | Cut: save the selection, then delete it from the page | — |
| Long press a filled slot | Clear it | Clear it |

A paste lands in the same spot on the page where the content was copied, and comes back already lasso-selected so you can move it right away.

Clipboards hold handwriting and shapes. Titles, images, links, and text boxes can't be saved. If a selection mixes them, Notipal skips what it can't save, saves the rest, and tells you what it skipped.

The same eight slots are shared with Mosaic, and you can copy them all to another Supernote. See [Clipboard Sync](#clipboard-sync).

### Smart Lasso

Smart Lasso works in two ways, depending on whether something is already selected.

**With a note lasso selection.** Notipal collects the selection and opens the Send panel. Handwriting is recognized as text, and text boxes, images, and links come along too.

**Without a selection.** Draw around any area of the screen with your pen. A frame appears around it. Drag its edges, corners, or move handle to adjust it, and then choose what to do with it:

- **Send to devices**: send the area as an image over LocalSend.
- **Add to Doc Screenshots**: save it to the screenshot history.
- **Mosaic card**: turn it into an image card in Mosaic. Mosaic opens right away.

The frame closes by itself if you leave it alone for 30 seconds.

Long press Smart Lasso to sync your clipboards to another device. See [Clipboard Sync](#clipboard-sync).

### Document Screenshots

Doc Screenshot behaves differently in Documents and in Notes, so you capture in one and insert in the other.

**In a Document, tap Doc Screenshot** to capture the screen and open the crop editor. Adjust the frame, choose a filter, and then pick an action:

| Action | Result |
| --- | --- |
| Insert Next | Queue the crop as the next screenshot to insert into a note |
| Add to Queue | Save the crop to the screenshot history |
| Send to Other Devices | Send the crop over LocalSend |
| Stitch | Start a long screenshot, or add this page to one already in progress |

Turn on **Multi** to make several crops from the same capture without closing the editor.

The filters are **Original**, **Enhance**, and **B&W Text**, with an ink density from 100% down to 20%. They're designed for an E Ink screen.

**Stitching** joins pages into one image. Capture a page, turn the page, and stitch again. Once two or more pages are in, the stitch editor opens:

- **Long** stacks the pages into one strip. Drag a seam to set the overlap, or tap **Auto Align** to match the pages for you.
- **Grid** lays the pages out in columns, ordered by row or by column.
- **Add Page**, **Remove Last**, and **Reverse** change which pages are in it and in what order.

A stitch you leave unfinished is kept for 15 minutes.

**In a Note, tap Doc Screenshot** to insert the next queued screenshot. If nothing is queued, the screenshot panel opens. Long press Doc Screenshot to open the panel at any time. It has two tabs:

- **Queue**: crops waiting to be inserted.
- **History**: your last 20 saved crops.

Use **Select Multiple** to insert or delete several screenshots at once.

### Insert Image

Insert Image opens a panel with two tabs:

- **Received**: images sent to this Supernote over LocalSend.
- **Browse**: images in the Document, EXPORT, MyStyle, and Note folders.

Insert an image as it is, or use **Crop & Insert** to trim it and apply the same filters as Doc Screenshot. **Select Multiple** inserts several images at once.

When Mosaic is open, inserted images become Mosaic image cards.

### Text Receive

Text Receive puts text from another device straight onto the page you're writing on.

1. Tap **Text Receive** in the side column. A small bubble appears.
2. Drag the bubble to where the text should go.
3. Send text from LocalSend on your phone or computer.

Each message arrives as a text box at the bubble's position. More messages stack below it and skip past anything already on the page. Tap the bubble to place text that's still waiting. Long press the bubble to throw that text away.

Text Receive works in Notes and in Mosaic, where each message becomes a text card. It stops automatically when you switch notes, change the page structure, or open a document. If text arrives while Text Receive is off, the sender's transfer is declined and Notipal reminds you to turn it on.

### Ink Palette

Ink Palette restyles handwriting you've already written.

**Pen slots.** Save up to nine pens. Each slot has a pen type, a color, and a thickness:

| Setting | Options |
| --- | --- |
| Pen type | Needle, Ink Pen, Brush, Marker |
| Color | Black, Dark Gray, Light Gray, White |
| Thickness | Thin, Medium, Thick, and the pen's own sizes |

A marker's color is set by which marker you choose. Lock a slot to keep it when you reset the rest.

**Restyle.** Lasso some strokes and tap Ink Palette to edit them in the full panel. Without a selection, tap Ink Palette to show a small slot bubble instead. Lasso strokes, then tap a slot on the bubble to apply that pen in one step. Long press Ink Palette to open the full panel at any time.

Only handwriting can be restyled. If the selection includes titles, links, text boxes, or images, Notipal won't change it.

**Restore points.** Before every restyle, Notipal saves a snapshot of the page. You can also tap **Create** to save one yourself. The latest three are kept. Tap one to paste it back onto the page.

On the Mosaic whiteboard, Ink Palette restyles Mosaic strokes.

### AI Relay

AI Relay connects your Supernote to **RikkaHub** running on an Android phone. The AI runs on the phone. The Supernote sends the question and shows the reply.

**Pairing.** In RikkaHub, turn on the web server and turn **off** its authentication. With both devices on the same Wi-Fi, tap **AI Relay** on the toolbar. When Notipal finds the phone, it asks whether to connect. After that, it reconnects to the same phone by itself.

**Asking.** Turning on AI Relay opens the AI bubble, which has these actions:

- **Ask from selection** has two modes:
  - **Text query**: recognizes the handwriting you lasso and sends it as a question.
  - **Formula**: sends your handwritten math as an image. The reply has a transcription in LaTeX and a short solution.

  If nothing is selected, circle the content with the pen first.
- **Ask from image**: draw a frame around any part of the screen and send it as an image.
- **Conversations**: open the AI Relay inbox.

If the phone is offline, questions are queued and sent when it reconnects. A reply that doesn't arrive within 90 seconds is reported as a timeout.

**Replies.** The newest replies appear as cards under the bubble. Tap a card to read it with Markdown and formulas rendered. Then you can:

- **Select** some lines and **Insert** them into the note.
- **Replace handwriting**: for a formula reply, swap your handwritten math for typeset formulas.
- **Delete** the reply.

In the inbox, tap **Edit** to select several messages. Then **Re-add** puts them back on the bubble and **Delete** removes them.

In Documents, AI Relay can answer questions but can't insert into the page, and Formula mode isn't available.

## Sharing with LocalSend

Notipal includes its own LocalSend server, so other devices can find your Supernote as **Supernote** in the LocalSend app. It starts automatically while the toolbar is open and Wi-Fi is connected.

### Receiving

What you send decides where it goes:

| You send | It goes to |
| --- | --- |
| Text | The Text Receive bubble, if it's on |
| One image, while a note is open | Notipal asks: **Insert now**, keep it in the inbox, or reject it |
| Other images and files | `/sdcard/INBOX` |
| A plugin package (`.snplg`) | `/sdcard/MyStyle` |
| A clipboard sync | Your eight clipboard slots, after you confirm |

Received images show up in the **Received** tab of Insert Image.

### Sending

The Send panel opens from Smart Lasso, from the Doc Screenshot editor, and from **Send to other devices** in a document's text menu.

Check the items you want to send, which can be text, images, or links. Then pick a device. Notipal scans the network for devices with LocalSend open, and **Rescan** looks again.

Long press a device to make it your **default**. After that, a single image or a single piece of text goes straight to it without opening the panel.

### Clipboard Sync

**Sync Clipboard** packs all eight clipboard slots and sends them to Notipal on another Supernote. The other device asks before it accepts.

Long pressing Smart Lasso is a shortcut. It syncs to your default device, or opens the Send panel ready to sync if you haven't set one.

## Use with Mosaic

[Mosaic](https://github.com/Not-Just-Supernote/sn-plugin-mosaic) is a whiteboard plugin for Supernote. Install it next to Notipal and the two work together.

- **Note to Mosaic**: in a note, this side-column button reads the whole note and opens it in Mosaic.
- **Smart Lasso → Mosaic card**: any area of the screen becomes an image card in Mosaic.
- **Mosaic mode**: while Mosaic is open, the toolbar switches to the Mosaic tool set.
  - Images and doc screenshots arrive as image cards.
  - Received text becomes text cards.
  - Ink Palette restyles Mosaic strokes.
- **Shared clipboards**: the eight slots work on the whiteboard too. Copy strokes in a note and paste them in Mosaic, or the other way around.

## Limits

- **Screen rotation**: after rotating the screen, close Notipal and open it again. For now, it's best not to rotate while using it.
- **Wi-Fi**: sending, receiving, and AI Relay all need it.
- **RikkaHub authentication**: AI Relay can't connect while it's on.
- **Documents**: Text Receive, AI insertion, and Formula mode only work in Notes.

## Build from Source

Requirements:

- Node.js 18 or newer
- The Android SDK, NDK, and CMake. Ink filters are a small C++ library.
- Python 3

```bash
npm install
./buildPlugin.sh            # macOS / Linux
./buildPlugin.ps1           # Windows PowerShell
```

The package is written to `build/outputs/plugin_notipal.snplg`. Copy it to your Supernote, or send it over LocalSend from a device already running Notipal.

| Path | Contents |
| --- | --- |
| `index.js`, `App.tsx`, `components/` | Plugin entry, toolbar actions, clipboards, text insertion, AI and formula flows (React Native) |
| `android/app/src/main/java/me/laumss/notipal/` | Floating toolbar, panels, overlays, LocalSend server, AI Relay, Mosaic link (Kotlin) |
| `android/app/src/main/cpp/` | Ink filter used by Enhance and B&W Text |
| `android/app/src/main/assets/formula/` | MathJax, used to render formulas offline |

## Community

Join the [Notipal Discord](https://discord.gg/Z8NwyGWrbj) to share tips, report problems, and get updates first.

## License

[MIT](LICENSE)

Notipal is not an official plugin. Supernote and Ratta are not responsible for anything that happens while you use it. All icon resources and logic code used by this plugin are the property of Ratta, and may not be used for any commercial purpose.
