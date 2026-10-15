-- Editing branding/menu icons requires the file upload action as well as the page save action.
UPDATE proto_menu m SET buttons=(
 SELECT jsonb_agg(CASE WHEN b->>'id' IN ('add','update') THEN jsonb_set(b,'{permissions}',COALESCE(b->'permissions','[]'::jsonb)||'[{"permission":"file","actions":["save"]}]'::jsonb) ELSE b END)::text
 FROM jsonb_array_elements(m.buttons::jsonb) b
) WHERE m.id IN ('system-basis','system-menu');
