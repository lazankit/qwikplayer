-- "Next Episode" button: appears in the last 45 s of an episode (when the playlist has
-- a next one), and plays the next episode when clicked.
-- Ctrl+N (script-binding next_episode/go) does the same at any time.
--
-- When the end credits were detected (skip-intro.lua publishes user-data/qwik/credits) the button shows as
-- they start and a countdown plays the next episode 10 s later, but only if playback itself crosses that
-- point: a seek past it, a paused player or a cancelled countdown never jumps. Esc or a click on
-- "Watch credits" cancels the countdown for the current episode.
-- The button stays out of the way while the Audio & Subtitles popup, the Episodes drawer or a uosc menu
-- is open, and stacks above Skip Intro if both would show.

local SHOW_BEFORE = 45          -- seconds before the end
local COUNTDOWN = 10            -- seconds from the start of the credits to the automatic switch
local ov = mp.create_osd_overlay("ass-events")
local visible, box, cbox, hover_target = false, nil, nil, nil
local armed, suppressed, last_seek = false, false, -10   -- armed: a countdown is running and may auto-advance
local esc_bound, last_key, nxt_name = false, nil, ""
local timer

local function next_entry()
  local list = mp.get_property_native("playlist") or {}
  local pos = mp.get_property_number("playlist-pos", -1)
  return list[pos + 2], pos
end

-- Cheap test for "there is a next episode" (does not copy the whole playlist).
local function has_next()
  local pos = mp.get_property_number("playlist-pos", -1)
  local count = mp.get_property_number("playlist-count", 0)
  return pos >= 0 and pos < count - 1
end

local function blocked()
  return mp.get_property_native("user-data/qwik/modal") == true
    or mp.get_property_native("user-data/uosc/menu/type") ~= nil
end

-- "The Americans S01E08 Mutually Assured Destruction.mkv" -> "S01E08 · Mutually Assured Destruction"
local function label(file)
  local name = (file or ""):match("([^/\\]+)$") or ""
  name = name:gsub("%.%w+$", ""):gsub("[%._]", " ")
  local s, e, rest = name:match("[Ss](%d+)%s*[Ee](%d+)(.*)$")
  if not s then return name end
  rest = rest:gsub("%f[%w]%d%d%d%d?p%f[%W].*$", ""):gsub("[%[%(].*$", ""):gsub("^[%s%-]+", ""):gsub("[%s%-]+$", "")
  return string.format("S%02dE%02d", tonumber(s), tonumber(e)) .. (rest ~= "" and ("  ·  " .. rest) or "")
end

local function rrect(x, y, w, h, r, color, alpha)
  return string.format("{\\an7\\pos(%d,%d)\\bord0\\shad0\\1c&H%s&\\1a&H%s&\\p1}m %d 0 l %d 0 b %d 0 %d 0 %d %d l %d %d b %d %d %d %d %d %d l %d %d b 0 %d 0 %d 0 %d l 0 %d b 0 0 0 0 %d 0{\\p0}",
    x, y, color, alpha, r, w - r, w, w, w, r, w, h - r, w, h, w, h, w - r, h, r, h, h, h, h - r, r, r)
end

local tick, on_mouse, cancel

local function set_timer(period)
  if not period then if timer then timer:kill() end return end
  if not timer then timer = mp.add_periodic_timer(period, function() tick() end) return end
  if timer.timeout ~= period then timer:kill(); timer.timeout = period; timer:resume()
  elseif not timer:is_enabled() then timer:resume() end
end

-- The timer runs only when this file is long enough and has a next episode: 1 s while waiting,
-- 0.25 s while the button shows.
local function update_timer()
  local dur = mp.get_property_number("duration")
  if dur and dur >= 300 and has_next() then set_timer(visible and 0.25 or 1) else set_timer(nil) end
end

local function bind_esc(on)
  if on == esc_bound then return end
  esc_bound = on
  if on then mp.add_forced_key_binding("ESC", "next_episode_cancel", function() cancel() end)
  else mp.remove_key_binding("next_episode_cancel") end
end

local function hide()
  if not visible then bind_esc(false) return end
  visible, box, cbox, hover_target, last_key = false, nil, nil, nil, nil
  mp.remove_key_binding("next_episode_click")
  bind_esc(false)
  mp.unobserve_property(on_mouse)
  ov:remove()
  update_timer()
end

local function go()
  local nxt = next_entry()
  if not nxt then mp.osd_message("This is the last episode") return end
  armed = false
  hide()
  mp.commandv("playlist-next", "force")
end

cancel = function()
  suppressed, armed = true, false
  bind_esc(false)
  mp.osd_message("Auto-play cancelled", 1.5)
  tick()
end

local function draw(left, total, counting)
  local w, h = mp.get_osd_size()
  if not w or w == 0 then return end
  local s = h / 1080
  local bw, bh = math.floor(360 * s), math.floor(92 * s)
  local x, y = math.floor(w - bw - 48 * s), math.floor(h - bh - 190 * s)
  if mp.get_property_native("user-data/qwik/skip-visible") == true then
    y = y - math.floor((90 + 16) * s)   -- sit above the Skip Intro button instead of on top of it
  end
  box = { x = x, y = y, w = bw, h = bh }
  local p = math.max(0, math.min(1, 1 - left / total))
  local fs = math.floor(34 * s)
  local over_main, over_cancel = hover_target == "go", hover_target == "cancel"
  local sub = (nxt_name:sub(1, 38)) .. (counting and string.format("   ·   %ds", math.max(0, math.ceil(left))) or "")
  local out = {
    rrect(x, y + math.floor(8 * s), bw, bh, math.floor(10 * s), "000000", "70"),                       -- shadow
    rrect(x, y, bw, bh, math.floor(10 * s), over_main and "FFFFFF" or "E6E6E6", "00"),                 -- light button
    rrect(x, y + bh - math.floor(6 * s), math.floor(bw * p), math.floor(6 * s), 1, "2B8AFF", "00"),    -- countdown bar (Ember)
    string.format("{\\an4\\pos(%d,%d)\\fnPoppins\\b1\\fs%d\\bord0\\shad0\\1c&H141414&}▶  Next Episode", x + math.floor(26 * s), y + math.floor(32 * s), fs),
    string.format("{\\an4\\pos(%d,%d)\\fnPoppins\\fs%d\\bord0\\shad0\\1c&H555555&}%s", x + math.floor(26 * s), y + math.floor(64 * s), math.floor(24 * s), sub),
  }
  cbox = nil
  if counting then
    local cw, ch = math.floor(250 * s), math.floor(38 * s)
    local cx, cy = x + bw - cw, y - ch - math.floor(10 * s)
    cbox = { x = cx, y = cy, w = cw, h = ch }
    table.insert(out, rrect(cx, cy, cw, ch, math.floor(8 * s), over_cancel and "FF3874" or "1A1A1A", over_cancel and "00" or "40"))
    table.insert(out, string.format("{\\an5\\pos(%d,%d)\\fnPoppins\\fs%d\\bord0\\shad0\\1c&HFFFFFF&}Watch credits   ·   Esc",
      cx + math.floor(cw / 2), cy + math.floor(ch / 2), math.floor(22 * s)))
  end
  local data = table.concat(out, "\n")
  local key = w .. "x" .. h .. data
  if key == last_key then return end   -- nothing changed: don't re-send the overlay
  last_key = key
  ov.res_x, ov.res_y = w, h
  ov.data = data
  ov:update()
end

local function in_box(b)
  if not b then return false end
  local m = mp.get_property_native("mouse-pos") or {}
  return m.x and m.x >= b.x and m.x <= b.x + b.w and m.y >= b.y and m.y <= b.y + b.h
end

tick = function()
  if blocked() then armed = false; hide() return end
  local dur = mp.get_property_number("duration")
  local pos = mp.get_property_number("time-pos")
  if not dur or not pos or dur < 300 or not has_next() then armed = false; hide() return end
  local left, total, counting = dur - pos, SHOW_BEFORE, false
  local credits = mp.get_property_native("user-data/qwik/credits")
  if type(credits) == "number" and credits > 0 then
    -- End credits found (skip-intro.lua): offer the next episode as they start and play it 10 s later,
    -- but only when playback itself runs through the mark (not after a seek, not while paused).
    if pos < credits - 0.5 then armed = false; hide() return end
    total = COUNTDOWN
    left = credits + total - pos
    local settled = not mp.get_property_bool("seeking", false) and (mp.get_time() - last_seek) > 1
    if left > 0 then
      -- arm only when playback reaches the mark by itself: a seek landing inside the window must not auto-advance
      if settled and not suppressed and pos - credits < 3 then armed = true end
    else
      if armed and not suppressed and settled and not mp.get_property_bool("pause", false) then
        go() return
      end
      armed = false
    end
    counting = armed and left > 0 and not suppressed
  else
    armed = false
    if left > SHOW_BEFORE or left < 0 then hide() return end
  end
  if not visible then
    visible = true
    local nxt = next_entry()
    nxt_name = label(nxt and nxt.filename)
    mp.observe_property("mouse-pos", "native", on_mouse)
    update_timer()
  end
  bind_esc(counting)
  -- Clicks only go to the button while the pointer is on it; elsewhere the video keeps its usual click.
  local target = in_box(box) and "go" or (counting and in_box(cbox) and "cancel") or nil
  if target ~= hover_target then
    hover_target = target
    mp.remove_key_binding("next_episode_click")
    if target then mp.add_forced_key_binding("MBTN_LEFT", "next_episode_click", target == "go" and go or cancel) end
  end
  draw(math.max(0, left), total, counting)
end

on_mouse = function() if visible then tick() end end

mp.register_event("file-loaded", function()
  armed, suppressed = false, false
  hide()
  update_timer()
end)
mp.register_event("seek", function() armed = false; last_seek = mp.get_time() end)
local function on_change() update_timer(); if visible then tick() end end
mp.observe_property("duration", "native", on_change)
mp.observe_property("playlist-pos", "native", on_change)
mp.observe_property("playlist-count", "native", on_change)
mp.observe_property("user-data/qwik/credits", "native", on_change)
mp.observe_property("user-data/qwik/modal", "native", function() if visible and blocked() then armed = false; hide() end end)
mp.observe_property("user-data/uosc/menu/type", "native", function() if visible and blocked() then armed = false; hide() end end)
mp.add_key_binding(nil, "go", go)
