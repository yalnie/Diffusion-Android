#include "build.h"

// a1ba: keep it win32 only for now
#if XASH_64BIT && XASH_WIN32

// OpenAG
#include <cstdint>
#include <memory>
#include <string>
#include <time.h>

#include <discord-rpc/include/discord_rpc.h>

#include "hud.h"
#include "utils.h"

#include "discord.h"

using namespace std::literals;

namespace discord_integration
{
	namespace
	{
		// From Discord developer dashboard.
		constexpr const char CLIENT_ID[] = "1224041387654385695";

		// This seems to be consistent across PCs.
		constexpr const char STEAM_APP_ID[] = "10";

		// For tracking if we're in-game.
		bool updated_client_data = false;

		// Possible game states.
		enum class game_state
		{
			NOT_PLAYING = 0,
			PLAYING,
			SPECTATING,
		};

		// Class that handles tracking state changes.
		class DiscordState {
		public:
			DiscordState()
				: cur_state( game_state::NOT_PLAYING )
				, gamemode()
				, player_count( 0 )
				, player_limit( 0 )
				, dirty( true )
			{
				update_presence();
			};

			inline void set_game_state( game_state new_game_state )
			{
				if( cur_state != new_game_state )
				{
					cur_state = new_game_state;

					if( new_game_state == game_state::NOT_PLAYING )
					{
						gamemode.clear();
					}
					else
					{
						refresh_player_stats();
					}

					dirty = true;
				}
			}

			inline game_state get_game_state() const
			{
				return cur_state;
			}

			inline void set_gamemode( std::string new_gamemode )
			{
				if( gamemode != new_gamemode )
				{
					gamemode = std::move( new_gamemode );
					dirty = true;
				}
			}

			inline void set_player_count( int new_player_count )
			{
				if( player_count != new_player_count )
				{
					player_count = new_player_count;
					dirty = true;
				}
			}

			inline void set_player_limit( int new_player_limit )
			{
				if( player_limit != new_player_limit )
				{
					player_limit = new_player_limit;
					dirty = true;
				}
			}

			inline void refresh_player_stats()
			{
				set_player_count( static_cast<int>( get_player_count() ) );
				set_player_limit( gEngfuncs.GetMaxClients() );
			}

			inline void refresh()
			{
				dirty = true;
			}

			inline void update_presence_if_dirty()
			{
				if( dirty )
					update_presence();
			}

		protected:
			void update_presence()
			{
				dirty = false;

				DiscordRichPresence presence{};

				presence.largeImageKey = "gameicon";
				presence.smallImageKey = "gameicon";
				presence.largeImageText = "https://moddb.com/mods/diffusion";
				presence.smallImageText = "";

				static int64_t gametime = time( 0 );
				presence.startTimestamp = gametime;

				if( cur_state != game_state::NOT_PLAYING )
				{
					char map[256] = {};
					char ServerName[256];
					char *szServerName = gHUD.m_szServerName;
					if( gEngfuncs.GetMaxClients() <= 1 )
					{
						ServerName[0] = '\0';
					}
					else
					{
						Q_sprintf( ServerName, "Server: " );
						get_map_name( map, sizeof( map ) );
					}

					Q_strncat( ServerName, szServerName, sizeof( ServerName ));
					remove_color_characters( ServerName );
					presence.details = ServerName;
					presence.state = map;
					if( gEngfuncs.GetMaxClients() <= 1 )
					{
						presence.partySize = 0;
						presence.partyMax = 0;
					}
					else
					{
						presence.partySize = player_count;
						presence.partyMax = player_limit;
					}
				}
				else
					presence.state = "In Menus";

				Discord_UpdatePresence( &presence );
			}

			game_state cur_state;
			std::string gamemode;
			int player_count;
			int player_limit;

			// Flag indicating there are some changes not sent to Discord yet.
			bool dirty;
		};

		// Pointer so the constructor doesn't run too early.
		std::unique_ptr<DiscordState> discord_state;

		// Time of the last update.
		float last_update_time;

		void handle_ready( const DiscordUser * )
		{
			gEngfuncs.Con_Printf( "Connected to Discord.\n" );
		}

		void handle_errored( int error_code, const char *message )
		{
			gEngfuncs.Con_Printf( "Discord error (%d): %s\n", error_code, message );
		}

		void handle_disconnected( int error_code, const char *message )
		{
			gEngfuncs.Con_Printf( "Disconnected from Discord (%d): %s\n", error_code, message );
		}
	}

	void initialize()
	{
		DiscordEventHandlers handlers{};

		Discord_Initialize( CLIENT_ID, &handlers, 1, STEAM_APP_ID );

		discord_state = std::make_unique<DiscordState>();

		Discord_RunCallbacks();

		last_update_time = gEngfuncs.GetClientTime();
	}

	void shutdown()
	{
		discord_state.reset();
		Discord_Shutdown();
	}

	void set_spectating( bool spectating )
	{
		if( spectating )
			discord_state->set_game_state( game_state::SPECTATING );
		else
			discord_state->set_game_state( game_state::PLAYING );
	}


	void set_gamemode( std::string gamemode )
	{
		discord_state->set_gamemode( std::move( gamemode ) );
	}

	void on_update_client_data()
	{
		updated_client_data = true;
	}

	void on_frame()
	{
		if( !updated_client_data )
			discord_state->set_game_state( game_state::NOT_PLAYING );
		else if( discord_state->get_game_state() == game_state::NOT_PLAYING )
			// Only set this if we weren't playing, otherwise we might overwrite some other state.
			discord_state->set_game_state( game_state::PLAYING );

		updated_client_data = false;

		// Check player counts for updates every now and then.
		const auto current_time = gEngfuncs.GetClientTime();
		if( last_update_time < current_time || last_update_time - current_time > 5 )
		{
			last_update_time = current_time;
			on_player_count_update();
			discord_state->update_presence_if_dirty();
		}

		Discord_RunCallbacks();
	}

	void on_player_count_update()
	{
		discord_state->refresh_player_stats();
	}

	void update()
	{
		discord_state->refresh();
	}
}

#elif defined(__ANDROID__)

#include <cstdint>
#include <memory>
#include <string>
#include <time.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdio>
#include <cstring>

#include "hud.h"
#include "utils.h"
#include "discord.h"

namespace discord_integration
{
	namespace
	{
		constexpr int DISCORD_UDP_PORT = 27421;

		bool updated_client_data = false;

		enum class game_state
		{
			NOT_PLAYING = 0,
			PLAYING,
			SPECTATING,
		};

		class DiscordState {
		public:
			DiscordState()
				: cur_state( game_state::NOT_PLAYING )
				, gamemode()
				, player_count( 0 )
				, player_limit( 0 )
				, dirty( true )
				, sock_fd( -1 )
			{
				init_socket();
				update_presence();
			}

			~DiscordState()
			{
				if( sock_fd >= 0 )
				{
					char buf[256];
					snprintf( buf, sizeof( buf ),
						"{\"state\":\"In Menus\",\"details\":\"\",\"partySize\":0,\"partyMax\":0,\"startTime\":0}" );
					send_packet( buf );
					close( sock_fd );
					sock_fd = -1;
				}
			}

			inline void set_game_state( game_state new_game_state )
			{
				if( cur_state != new_game_state )
				{
					cur_state = new_game_state;

					if( new_game_state == game_state::NOT_PLAYING )
					{
						gamemode.clear();
					}
					else
					{
						refresh_player_stats();
					}

					dirty = true;
				}
			}

			inline game_state get_game_state() const
			{
				return cur_state;
			}

			inline void set_gamemode( std::string new_gamemode )
			{
				if( gamemode != new_gamemode )
				{
					gamemode = std::move( new_gamemode );
					dirty = true;
				}
			}

			inline void set_player_count( int new_player_count )
			{
				if( player_count != new_player_count )
				{
					player_count = new_player_count;
					dirty = true;
				}
			}

			inline void set_player_limit( int new_player_limit )
			{
				if( player_limit != new_player_limit )
				{
					player_limit = new_player_limit;
					dirty = true;
				}
			}

			inline void refresh_player_stats()
			{
				set_player_count( static_cast<int>( get_player_count() ) );
				set_player_limit( gEngfuncs.GetMaxClients() );
			}

			inline void refresh()
			{
				dirty = true;
			}

			inline void update_presence_if_dirty()
			{
				if( dirty )
					update_presence();
			}

		protected:
			void init_socket()
			{
				sock_fd = socket( AF_INET, SOCK_DGRAM, 0 );
				if( sock_fd >= 0 )
				{
					int flags = fcntl( sock_fd, F_GETFL, 0 );
					if( flags >= 0 )
						fcntl( sock_fd, F_SETFL, flags | O_NONBLOCK );

					memset( &target_addr, 0, sizeof( target_addr ) );
					target_addr.sin_family = AF_INET;
					target_addr.sin_port = htons( DISCORD_UDP_PORT );
					inet_pton( AF_INET, "127.0.0.1", &target_addr.sin_addr );
				}
			}

			void send_packet( const char *json_str )
			{
				if( sock_fd < 0 )
					init_socket();

				if( sock_fd >= 0 && json_str )
				{
					sendto( sock_fd, json_str, strlen( json_str ), 0,
						reinterpret_cast<struct sockaddr *>( &target_addr ), sizeof( target_addr ) );
				}
			}

			void escape_json_string( const char *src, char *dest, size_t dest_size )
			{
				if( !dest || dest_size == 0 ) return;
				size_t j = 0;
				for( size_t i = 0; src && src[i] != '\0' && j + 2 < dest_size; ++i )
				{
					if( src[i] == '"' || src[i] == '\\' )
					{
						dest[j++] = '\\';
						dest[j++] = src[i];
					}
					else if( src[i] == '\n' || src[i] == '\r' || src[i] == '\t' )
					{
						dest[j++] = ' ';
					}
					else
					{
						dest[j++] = src[i];
					}
				}
				dest[j] = '\0';
			}

			void update_presence()
			{
				dirty = false;

				static int64_t gametime = time( nullptr );

				char state[256] = "In Menus";
				char details[256] = "";
				int pSize = 0;
				int pLimit = 0;

				if( cur_state != game_state::NOT_PLAYING )
				{
					char map[256] = {};
					char ServerName[256] = {};
					char *szServerName = gHUD.m_szServerName;
					get_map_name( map, sizeof( map ) );

					if( gEngfuncs.GetMaxClients() > 1 )
					{
						Q_sprintf( ServerName, "Server: " );
						Q_strncat( ServerName, szServerName, sizeof( ServerName ) );
						remove_color_characters( ServerName );
						pSize = player_count;
						pLimit = player_limit;
					}

					if( map[0] != '\0' )
						escape_json_string( map, state, sizeof( state ) );
					else
						Q_strncpy( state, "In Menus", sizeof( state ) );
					escape_json_string( ServerName, details, sizeof( details ) );
				}

				char payload[1024];
				snprintf( payload, sizeof( payload ),
					"{\"state\":\"%s\",\"details\":\"%s\",\"partySize\":%d,\"partyMax\":%d,\"startTime\":%lld}",
					state, details, pSize, pLimit, static_cast<long long>( gametime ) );

				send_packet( payload );
			}

			game_state cur_state;
			std::string gamemode;
			int player_count;
			int player_limit;
			bool dirty;
			int sock_fd;
			struct sockaddr_in target_addr;
		};

		std::unique_ptr<DiscordState> discord_state;
		float last_update_time = 0.0f;
		float last_client_data_time = 0.0f;
	}

	void initialize()
	{
		discord_state = std::make_unique<DiscordState>();
		last_update_time = gEngfuncs.GetClientTime();
		last_client_data_time = last_update_time;
	}

	void shutdown()
	{
		discord_state.reset();
	}

	void set_spectating( bool spectating )
	{
		if( discord_state )
		{
			if( spectating )
				discord_state->set_game_state( game_state::SPECTATING );
			else
				discord_state->set_game_state( game_state::PLAYING );
		}
	}

	void set_gamemode( std::string gamemode )
	{
		if( discord_state )
			discord_state->set_gamemode( std::move( gamemode ) );
	}

	void on_update_client_data()
	{
		updated_client_data = true;
	}

	void on_frame()
	{
		if( !discord_state ) return;

		const auto current_time = gEngfuncs.GetClientTime();

		if( updated_client_data )
		{
			last_client_data_time = current_time;
			if( discord_state->get_game_state() == game_state::NOT_PLAYING )
				discord_state->set_game_state( game_state::PLAYING );
			updated_client_data = false;
		}
		else if( (current_time - last_client_data_time > 3.0f) && (last_client_data_time > 0.0f) )
		{
			discord_state->set_game_state( game_state::NOT_PLAYING );
		}

		if( last_update_time < current_time || last_update_time - current_time > 5 )
		{
			last_update_time = current_time;
			on_player_count_update();
			discord_state->update_presence_if_dirty();
		}
	}

	void on_player_count_update()
	{
		if( discord_state )
			discord_state->refresh_player_stats();
	}

	void update()
	{
		if( discord_state )
			discord_state->refresh();
	}
}

#endif // Windows / Android
