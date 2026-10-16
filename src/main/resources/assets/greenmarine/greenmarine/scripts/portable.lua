function update(frame)
    marine.uniform("warmth", 0.08 + 0.02 * math.sin(frame.day * 6.283185307))
end
