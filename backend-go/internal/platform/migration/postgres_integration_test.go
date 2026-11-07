//go:build integration

package migration

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgconn"

	"personal-ai-os/backend-go/internal/platform/database"
)

// 此处只有隔离重放测试；不是生产迁移器，也不生成 Flyway 历史。
func TestPostgresOriginalMigrationsIntegration(t *testing.T) {
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" {
		t.Skip("需显式独立 PostgreSQL 测试环境")
	}
	settings := database.DefaultPostgresOptions()
	settings.Host, settings.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	settings.Username, settings.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || settings.Host != "127.0.0.1" || settings.Database != "personalos_go_test" || settings.Username != "go_fixture" {
		t.Fatal("仅允许脚本创建的专用回环测试库")
	}
	settings.Port, settings.SSLMode = port, "disable"
	if mode := os.Getenv("GO_TEST_POSTGRES_SSL_MODE"); mode != "" {
		settings.SSLMode = mode
		settings.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	}
	connector, err := database.NewPostgresConnector(settings)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	pool, err := database.Open(ctx, connector, database.DefaultPoolOptions(true))
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	scripts, err := LoadVersioned(os.DirFS(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration")))
	if err != nil || len(scripts) != 49 {
		t.Fatalf("必须加载全部 49 个原 SQL: %v", err)
	}
	for _, seeded := range []bool{false, true} {
		name := "empty"
		if seeded {
			name = "synthetic_upgrade"
		}
		t.Run(name, func(t *testing.T) {
			// 固定测试标识符，无用户输入；只有创建成功后才注册清理。
			schema := "go_replay_" + name
			if err := pool.WithConn(ctx, func(c *sql.Conn) error {
				_, err := c.ExecContext(ctx, "CREATE SCHEMA "+schema)
				return err
			}); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				cleanup, stop := context.WithTimeout(context.Background(), 10*time.Second)
				defer stop()
				if err := pool.WithConn(cleanup, func(c *sql.Conn) error {
					_, err := c.ExecContext(cleanup, "DROP SCHEMA "+schema+" CASCADE")
					return err
				}); err != nil {
					t.Error(err)
				}
			})
			inSchema := func(callback func(*sql.Tx) error) error {
				return pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
					if _, err := tx.ExecContext(ctx, "SET LOCAL search_path = "+schema+",public"); err != nil {
						return err
					}
					return callback(tx)
				})
			}
			execute := func(statement string, args ...any) error {
				return inSchema(func(tx *sql.Tx) error { _, err := tx.ExecContext(ctx, statement, args...); return err })
			}
			for _, script := range scripts {
				if !t.Run(script.Name, func(t *testing.T) {
					// 不按分号拆句：保留函数体、注释与完整文件；每个原迁移一个事务。
					if err := execute(script.SQL); err != nil {
						t.Fatal(err)
					}
					if seeded && script.Version == 16 {
						if err := execute(`INSERT INTO memory(id,content,created_at,updated_at)
							VALUES('00000000-0000-0000-0000-000000000016','历史记忆😀',now(),now())`); err != nil {
							t.Fatal(err)
						}
					}
					if seeded && script.Version == 20 {
						if err := execute(`INSERT INTO ai_model_config(id,base_url,api_key,chat_model,embedding_model,temperature,revision,updated_at)
							VALUES(1,'https://fixture.invalid/v1','fixture-not-a-secret',' fixture-chat ',' fixture-embed ',0.7,7,'2026-01-01T00:00:00Z')`); err != nil {
							t.Fatal(err)
						}
					}
				}) {
					t.Fatal("原迁移失败，停止后续重放")
				}
			}
			if os.Getenv("GO_TEST_FLYWAY") == "disposable" {
				t.Run("full schema definition diff with real Java Flyway", func(t *testing.T) {
					assertSchemaMatchesJavaFlyway(t, pool, ctx, schema)
				})
			}
			t.Run("complete schema and indexes", func(t *testing.T) {
				err := inSchema(func(tx *sql.Tx) error {
					expectStrings := func(query string, expected []string, args ...any) error {
						rows, err := tx.QueryContext(ctx, query, args...)
						if err != nil {
							return err
						}
						defer rows.Close()
						actual := []string{}
						for rows.Next() {
							var value string
							if err := rows.Scan(&value); err != nil {
								return err
							}
							actual = append(actual, value)
						}
						if err := rows.Err(); err != nil {
							return err
						}
						slices.Sort(expected)
						if !slices.Equal(actual, expected) {
							return fmt.Errorf("数据库结构不符: actual=%v expected=%v", actual, expected)
						}
						return nil
					}
					// 手工审查原 SQL 后列出最终表名；不从 Go 实现生成期望。
					tables := strings.Fields(`agent_followup agent_plan agent_plan_step agent_question
						ai_model_config ai_model_profile ai_model_profile_model calendar_event calendar_ics_identity chat_context_preference
						chat_message chat_message_citation chat_run chat_run_event chat_session confirmation
						knowledge_candidate_generation knowledge_capture knowledge_capture_version knowledge_conflict knowledge_conflict_audit knowledge_document
						knowledge_index_job knowledge_review_job knowledge_search_outbox knowledge_source knowledge_space
						maintenance_check memory operation_record project reminder reminder_broadcast_state reminder_webhook_delivery
						reminder_webhook_target runtime_heartbeat runtime_lease runtime_rate_bucket task task_tag token_usage vector_store
						web_push_delivery web_push_subscription work_signal_outbox workflow workflow_attempt workflow_execution`)
					if err := expectStrings("SELECT tablename FROM pg_tables WHERE schemaname=$1 ORDER BY tablename", tables, schema); err != nil {
						return err
					}
					triggers := strings.Fields(`candidate_generation_documents candidate_generation_sources candidate_generation_vectors
						knowledge_chunk_projection knowledge_chunk_review knowledge_document_projection knowledge_document_source
						knowledge_index_signal knowledge_review_signal knowledge_search_signal knowledge_source_revision
						review_attempt_reset search_projection_reset task_workspace_validate task_children_delete task_recurrence_generate
						knowledge_capture_job_complete report_session_protect report_message_protect report_run_protect workflow_prepare`)
					if err := expectStrings(`SELECT tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
						JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=$1 AND NOT t.tgisinternal ORDER BY tgname`, triggers, schema); err != nil {
						return err
					}
					if err := expectStrings(`SELECT extname FROM pg_extension WHERE extname IN ('vector','hstore','uuid-ossp','pgcrypto') ORDER BY extname`,
						[]string{"vector", "hstore", "uuid-ossp", "pgcrypto"}); err != nil {
						return err
					}
					if err := expectStrings(`SELECT c.relname||':'||format_type(a.atttypid,a.atttypmod) FROM pg_attribute a
						JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
						WHERE n.nspname=$1 AND c.relname IN ('vector_store','memory') AND a.attname='embedding' ORDER BY c.relname`,
						[]string{"memory:vector(1024)", "vector_store:vector(1024)"}, schema); err != nil {
						return err
					}
					return expectStrings(`SELECT indexname FROM pg_indexes WHERE schemaname=$1 AND indexdef LIKE '%USING hnsw%' AND indexdef LIKE '%vector_cosine_ops%' ORDER BY indexname`,
						[]string{"idx_memory_embedding_hnsw", "spring_ai_vector_index"}, schema)
				})
				if err != nil {
					t.Fatal(err)
				}
			})
			if seeded {
				t.Run("historical hash and model configuration survive", func(t *testing.T) {
					err := inSchema(func(tx *sql.Tx) error {
						var hash, key, protocol string
						var active bool
						var revision, models int
						if err := tx.QueryRowContext(ctx, "SELECT content_hash FROM memory WHERE id='00000000-0000-0000-0000-000000000016'").Scan(&hash); err != nil {
							return err
						}
						if hash != fmt.Sprintf("%x", sha256.Sum256([]byte("历史记忆😀"))) {
							return errors.New("V17 回填 UTF-8 SHA-256 不一致")
						}
						if err := tx.QueryRowContext(ctx, "SELECT api_key,active,revision,protocol FROM ai_model_profile WHERE name='默认配置'").Scan(&key, &active, &revision, &protocol); err != nil {
							return err
						}
						if key != "fixture-not-a-secret" || !active || revision != 7 || protocol != "OPENAI" {
							return errors.New("V21/V22 历史配置搬迁不一致")
						}
						if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM ai_model_profile_model WHERE
							(kind='CHAT' AND model_name='fixture-chat') OR (kind='EMBEDDING' AND model_name='fixture-embed')`).Scan(&models); err != nil {
							return err
						}
						if models != 2 {
							return errors.New("V23 历史模型清单搬迁不一致")
						}
						return nil
					})
					if err != nil {
						t.Fatal(err)
					}
				})
				return
			}
			t.Run("constraints and cascade", func(t *testing.T) {
				for _, statement := range []string{
					"INSERT INTO chat_session(conversation_id,title) VALUES('schema-probe','fixture')",
					"INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES('schema-probe','USER','中文😀',1)",
					"INSERT INTO chat_run(id,conversation_id,request,status) VALUES('first','schema-probe','{}','RUNNING')",
				} {
					if err := execute(statement); err != nil {
						t.Fatal(err)
					}
				}
				for _, invalid := range []struct{ query, code string }{
					{"INSERT INTO task(title,status) VALUES('bad','UNKNOWN')", "23514"},
					{"INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES('schema-probe','USER','duplicate',1)", "23505"},
					{"INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES('missing','USER','orphan',1)", "23503"},
					{"INSERT INTO chat_run(id,conversation_id,request,status) VALUES('second','schema-probe','{}','RUNNING')", "23505"},
				} {
					var pgError *pgconn.PgError
					if err := execute(invalid.query); !errors.As(err, &pgError) || pgError.Code != invalid.code {
						t.Fatalf("约束未生效，期望 SQLSTATE %s", invalid.code)
					}
				}
				if err := execute("UPDATE chat_run SET status='WAITING_USER' WHERE id='first'"); err != nil {
					t.Fatal("V33 WAITING_USER 状态必须被允许")
				}
				if err := execute("DELETE FROM chat_session WHERE conversation_id='schema-probe'"); err != nil {
					t.Fatal(err)
				}
				var remaining int
				if err := inSchema(func(tx *sql.Tx) error {
					return tx.QueryRowContext(ctx, "SELECT (SELECT count(*) FROM chat_message)+(SELECT count(*) FROM chat_run)").Scan(&remaining)
				}); err != nil || remaining != 0 {
					t.Fatal("会话删除必须级联清理消息与 run")
				}
			})
			t.Run("vector search and trigger chain", func(t *testing.T) {
				vector := "[1" + strings.Repeat(",0", 1023) + "]"
				err := inSchema(func(tx *sql.Tx) error {
					var before, after, document, chunk, nearest string
					if err := tx.QueryRowContext(ctx, "SELECT generation::text FROM knowledge_candidate_generation WHERE id=1").Scan(&before); err != nil {
						return err
					}
					if err := tx.QueryRowContext(ctx, `INSERT INTO knowledge_document(title,filename,content_type,size_bytes,char_count)
						VALUES('fixture','fixture.txt','text/plain',6,2) RETURNING id::text`).Scan(&document); err != nil {
						return err
					}
					if err := tx.QueryRowContext(ctx, `INSERT INTO vector_store(content,metadata,embedding)
						VALUES('fixture',json_build_object('document_id',$1::text),$2::vector) RETURNING id::text`, document, vector).Scan(&chunk); err != nil {
						return err
					}
					if err := tx.QueryRowContext(ctx, "SELECT id::text FROM vector_store ORDER BY embedding <=> $1::vector LIMIT 1", vector).Scan(&nearest); err != nil || nearest != chunk {
						return errors.New("1024 维向量余弦检索失败")
					}
					if _, err := tx.ExecContext(ctx, "UPDATE knowledge_review_job SET attempts=4,last_error='fixture' WHERE document_id=$1::uuid", document); err != nil {
						return err
					}
					if _, err := tx.ExecContext(ctx, "UPDATE knowledge_search_outbox SET claim_token=gen_random_uuid(),attempts=4,available_at=now()+interval '1 hour' WHERE chunk_id=$1::uuid", chunk); err != nil {
						return err
					}
					if _, err := tx.ExecContext(ctx, "UPDATE vector_store SET content='changed' WHERE id=$1::uuid", chunk); err != nil {
						return err
					}
					var sourceRevision, reviewRevision int
					var reviewReady, searchReady bool
					if err := tx.QueryRowContext(ctx, `SELECT s.revision,j.revision,j.attempts=0 AND j.last_error IS NULL
						FROM knowledge_source s JOIN knowledge_review_job j USING(document_id) WHERE document_id=$1::uuid`, document).Scan(&sourceRevision, &reviewRevision, &reviewReady); err != nil {
						return err
					}
					if err := tx.QueryRowContext(ctx, `SELECT claim_token IS NULL AND attempts=0 AND available_at<=clock_timestamp()
						FROM knowledge_search_outbox WHERE chunk_id=$1::uuid`, chunk).Scan(&searchReady); err != nil {
						return err
					}
					var signals int
					if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM work_signal_outbox WHERE
						(topic='review' AND item_id=$1::uuid) OR (topic='search' AND item_id=$2::uuid)`, document, chunk).Scan(&signals); err != nil {
						return err
					}
					if err := tx.QueryRowContext(ctx, "SELECT generation::text FROM knowledge_candidate_generation WHERE id=1").Scan(&after); err != nil {
						return err
					}
					if sourceRevision != 3 || reviewRevision != 3 || !reviewReady || !searchReady || signals != 2 || before == after {
						return errors.New("V35-V42 的来源/重试重置/搜索/唤醒/候选代数触发链不一致")
					}
					return nil
				})
				if err != nil {
					t.Fatal(err)
				}
				if err := execute("INSERT INTO vector_store(embedding) VALUES('[1,2,3]'::vector)"); err == nil {
					t.Fatal("向量维度不符必须在数据库失败")
				}
			})
			t.Run("failed transaction leaves no signal and no Flyway history", func(t *testing.T) {
				failure := errors.New("fixture rollback")
				err := inSchema(func(tx *sql.Tx) error {
					if _, err := tx.ExecContext(ctx, `INSERT INTO knowledge_document(id,title,filename,content_type,size_bytes,char_count)
						VALUES('00000000-0000-0000-0000-000000000099','rollback','fixture.txt','text/plain',1,1)`); err != nil {
						return err
					}
					return failure
				})
				if !errors.Is(err, failure) {
					t.Fatal(err)
				}
				var remaining int
				var noHistory bool
				if err := inSchema(func(tx *sql.Tx) error {
					return tx.QueryRowContext(ctx, `SELECT
						(SELECT count(*) FROM knowledge_document WHERE id='00000000-0000-0000-0000-000000000099')+
						(SELECT count(*) FROM work_signal_outbox WHERE item_id='00000000-0000-0000-0000-000000000099'),
						to_regclass($1) IS NULL`, schema+".flyway_schema_history").Scan(&remaining, &noHistory)
				}); err != nil || remaining != 0 || !noHistory {
					t.Fatal("回滚不能残留业务/唤醒记录，隔离重放不能伪造 Flyway 历史")
				}
			})
		})
	}
}
