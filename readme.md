# Ditto
Discord bot that lets you make any personality you'd like out of GPT-3.

## Usage
```
lein run -- [ditto/preset] [discord token]
```
`ditto` mode works as you'd expect it.
`preset` mode loads `preset-config.json` which should look like this:
```json
{
  "name": "Robbie",
  "personality": "Robbie is a person.",
  "command": "rob"
}
```
These things cannot be changed; `/botname` and `/personality` are disabled in this mode. command replaces `/gen` (in this case, it is now `/rob`.)

## Commands
* `/gen [response]` - get a response from the bot via OpenAI.
* `/nickname [name]` - set your nickname for the bot to call you.
* `/botname [name]` - set the bot's nickname.
* `/personality [content]` - Input a small bit of text speaking in third-person about the bot's personality. Refer to them by name.
* `/reset` - Reset the bot's memory of past messages from the current channel.

## Notes
* `OPENAI_TOKEN` is expected to contain your OpenAI token.
