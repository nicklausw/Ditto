# Ditto
Discord bot that lets you make any personality you'd like out of GPT-3.

## Usage
```
lein run -- [ditto/preset] [discord token]
```
`ditto` mode works as you'd expect it.
`preset` mode loads three things from files:
* Bot name from `name.txt`
* Bot personality from `personality.txt`
* Bot generation command in replacement of `/gen` (without the forward slash) in `generation-command.txt`
These things cannot be changed; `/botname` and `/personality` are disabled in this mode.

## Commands
* `/gen [response]` - get a response from the bot via OpenAI.
* `/nickname [name]` - set your nickname for the bot to call you.
* `/botname [name]` - set the bot's nickname.
* `/personality [content]` - Input a small bit of text speaking in third-person about the bot's personality. Refer to them by name.
* `/reset` - Reset the bot's memory of past messages from the current channel.

## Notes
* `OPENAI_TOKEN` is expected to contain your OpenAI token.
