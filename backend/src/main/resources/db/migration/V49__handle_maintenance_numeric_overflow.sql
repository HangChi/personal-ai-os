-- JSON 语法合法但数字超出 jsonb/numeric 范围时，仍返回有界校验失败。
CREATE OR REPLACE FUNCTION maintenance_parse(p_text text) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN RETURN p_text::jsonb;
EXCEPTION WHEN invalid_text_representation OR untranslatable_character
 OR character_not_in_repertoire OR program_limit_exceeded
 OR numeric_value_out_of_range THEN RETURN NULL;
END $$;
