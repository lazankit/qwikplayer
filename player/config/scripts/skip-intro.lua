-- "Skip Intro" button. Intro and credits times come from the player (Intros.cs compares the
-- audio of neighbouring episodes) via intros.json in the player's data folder, or from chapters named
-- Intro/Opening/OP. Click the button or press Enter while it shows.
-- Also publishes user-data/qwik/credits (start of the end credits) for next-episode.lua and
-- user-data/qwik/skip-visible (true while the button shows) so Next Episode can stack above it.
-- The button stays out of the way (no drawing, no key bindings) while the Audio & Subtitles popup, the
-- Episodes drawer or a uosc menu is open.

local utils = require "mp.utils"
local ov = mp.create_osd_overlay("ass-events")
local info, visible, hovering, box = nil, false, false, nil
local timer, past, last_key = nil, false, nil
local cache_mtime, cache_size, cache_data = nil, nil, nil

-- intros.json is in the player's data folder. The app passes that folder as script-opts qwik-data_dir;
-- without it the same rule as the app applies: %LOCALAPPDATA%\QwikPlayer\Player, or the folder from
-- before the rename (Oranix\Player) when only that one exists.
local function data_file(name)
  local dir = (mp.get_property_native("script-opts") or {})["qwik-data_dir"]
  if not dir or dir == "" then
    local base = os.getenv("LOCALAPPDATA") or ""
    local old = base .. "\\Oranix\\Player"
    dir = base .. "\\QwikPlayer\\Player"
    if not utils.file_info(dir) and utils.file_info(old) then dir = old end
  end
  return dir .. "\\" .. name
end
local FILE = data_file("intros.json")

local function norm(p) return (p or ""):gsub("/", "\\"):lower() end

local function blocked()
  return mp.get_property_native("user-data/qwik/modal") == true
    or mp.get_property_native("user-data/uosc/menu/type") ~= nil
end

local function from_chapters()
  local list = mp.get_property_native("chapter-list") or {}
  for i, c in ipairs(list) do
    local t = (c.title or ""):lower()
    if t:find("intro") or t:find("opening") or t == "op" or t:find("^op%s") then
      local nxt = list[i + 1]
      if nxt then return { introStart = c.time, introEnd = nxt.time } end
    end
  end
end

-- Parse intros.json only when the file changed (it is rewritten as episodes get analysed).
local function read_data()
  local fi = utils.file_info(FILE)
  if not fi then cache_mtime, cache_size, cache_data = nil, nil, nil return nil end
  if cache_data and cache_mtime == fi.mtime and cache_size == fi.size then return cache_data end
  local f = io.open(FILE, "r")
  if not f then return nil end
  local data = utils.parse_json(f:read("*a") or "")
  f:close()
  if type(data) ~= "table" then data = {} end
  cache_mtime, cache_size, cache_data = fi.mtime, fi.size, data
  return data
end

local tick

-- The timer runs only while this file has intro info and the intro isn't behind us:
-- 1 s while waiting for the intro, 0.25 s while the button shows.
local function set_timer(period)
  if not period then if timer then timer:kill() end return end
  if not timer then timer = mp.add_periodic_timer(period, function() tick() end) return end
  if timer.timeout ~= period then timer:kill(); timer.timeout = period; timer:resume()
  elseif not timer:is_enabled() then timer:resume() end
end

local function ensure_timer()
  if info and info.introStart and not past then set_timer(visible and 0.25 or 1) else set_timer(nil) end
end

local function load()
  info = from_chapters()
  local data = read_data()
  if data then
    local me = norm(mp.get_property("path"))
    for k, v in pairs(data) do
      if norm(k) == me then
        info = info or {}
        if not info.introStart and v.introStart and v.introStart >= 0 then info.introStart, info.introEnd = v.introStart, v.introEnd end
        if v.creditsStart and v.creditsStart > 0 then info.creditsStart = v.creditsStart end
      end
    end
  end
  mp.set_property_native("user-data/qwik/credits", info and info.creditsStart or -1)
  past = false
  ensure_timer()
end

local function rrect(x, y, w, h, r, color, alpha, extra)
  return string.format("{\\an7\\pos(%d,%d)\\bord0\\shad0%s\\1c&H%s&\\1a&H%s&\\p1}m %d 0 l %d 0 b %d 0 %d 0 %d %d l %d %d b %d %d %d %d %d %d l %d %d b 0 %d 0 %d 0 %d l 0 %d b 0 0 0 0 %d 0{\\p0}",
    x, y, extra or "", color, alpha, r, w - r, w, w, w, r, w, h - r, w, h, w, h, w - r, h, r, h, h, h, h - r, r, r)
end

local on_mouse

local function hide()
  if not visible then return end
  visible, hovering, box, last_key = false, false, nil, nil
  mp.remove_key_binding("skip_intro_click")
  mp.remove_key_binding("skip_intro_enter")
  mp.unobserve_property(on_mouse)
  mp.set_property_native("user-data/qwik/skip-visible", false)
  ov:remove()
  ensure_timer()
end

local function skip()
  if not info or not info.introEnd then return end
  hide()
  mp.commandv("seek", tostring(info.introEnd), "absolute+exact")
  mp.osd_message("Intro skipped", 1)
end

local function draw()
  local w, h = mp.get_osd_size()
  if not w or w == 0 then return end
  local s = h / 1080
  local bw, bh = math.floor(300 * s), math.floor(90 * s)
  local x, y = math.floor(w - bw - 48 * s), math.floor(h - bh - 190 * s)
  box = { x = x, y = y, w = bw, h = bh }
  local data = table.concat({
    -- Look: dark glass with a white outline; fills white on hover
    rrect(x, y, bw, bh, math.floor(8 * s), hovering and "FFFFFF" or "1A1A1A", hovering and "00" or "40",
      "\\bord" .. math.max(1, math.floor(3 * s)) .. "\\3c&HFFFFFF&\\3a&H00&"),
    string.format("{\\an5\\pos(%d,%d)\\fnPoppins\\b1\\fs%d\\bord0\\shad0\\1c&H%s&}Skip Intro", x + math.floor(bw / 2), y + math.floor(bh / 2),
      math.floor(36 * s), hovering and "141414" or "FFFFFF"),
  }, "\n")
  local key = w .. "x" .. h .. data
  if key == last_key then return end   -- nothing changed: don't re-send the overlay
  last_key = key
  ov.res_x, ov.res_y = w, h
  ov.data = data
  ov:update()
end

local function inside()
  if not box then return false end
  local m = mp.get_property_native("mouse-pos") or {}
  return m.x and m.x >= box.x and m.x <= box.x + box.w and m.y >= box.y and m.y <= box.y + box.h
end

tick = function()
  if blocked() then hide() return end
  local t = mp.get_property_number("time-pos")
  if not info or not info.introStart or not t or t < info.introStart - 0.5 then hide() return end
  if t > info.introEnd - 2 then
    past = true
    hide()
    ensure_timer()   -- stops it (past): nothing to show until the user seeks back
    return
  end
  if not visible then
    visible = true
    mp.set_property_native("user-data/qwik/skip-visible", true)
    mp.add_forced_key_binding("ENTER", "skip_intro_enter", skip)
    mp.observe_property("mouse-pos", "native", on_mouse)
    ensure_timer()
  end
  local over = inside()
  if over ~= hovering then
    hovering = over
    if over then mp.add_forced_key_binding("MBTN_LEFT", "skip_intro_click", skip)
    else mp.remove_key_binding("skip_intro_click") end
  end
  draw()
end

on_mouse = function() if visible then tick() end end

mp.register_event("file-loaded", function() hide(); load() end)
mp.register_event("seek", function() past = false; ensure_timer() end)
mp.register_script_message("intros-updated", function(path)
  -- the app may say which file was analysed; ignore updates for other files
  if path and path ~= "" and norm(path) ~= norm(mp.get_property("path")) then return end
  load()
end)
-- Drop the button's key bindings the moment a popup/drawer/menu opens (not up to a tick later).
local function on_modal() if visible and blocked() then hide() end end
mp.observe_property("user-data/qwik/modal", "native", on_modal)
mp.observe_property("user-data/uosc/menu/type", "native", on_modal)
mp.add_key_binding(nil, "skip", skip)
