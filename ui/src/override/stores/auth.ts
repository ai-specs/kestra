import {defineStore} from "pinia"

export class Me {
    hasAny(_permission: string, _namespace?: string) {
        return true
    }


    hasAnyAction(_permission: string, _action: string, _namespace?: string) {
        return true
    }


    isAllowed(_permission: string, _action: string, _namespace?: string) {
        return true
    }


    isAllowedGlobal(_permission: string, _action: string) {
        return true
    }


    hasAnyActionOnAnyNamespace(_permission: string, _action: string) {
        return true
    }

    hasAnyRole() {
        return true
    }

    getNamespacesForAction(_permission: string, _action: string): string[] {
        return []
    }
}

export interface AuthMethods {
    mailsEnabled?: boolean;
    passwordless?: boolean;
    loginPassword?: boolean;
    oauths?: string[];
}

export const useAuthStore = defineStore("auth", {
    state: () => ({
        user: new Me() as Me | undefined,
        isLogged: true,
        auths: undefined as AuthMethods | undefined,
    }),
    actions: {
        logout(){
            return Promise.resolve(true)
        },
        correction(){
            return Promise.resolve(true)
        },
        loadAuths(_options: Record<string, unknown>): Promise<AuthMethods | undefined> {
            // dsh fork：MCP OAuth 走内置 kestra-oidc 授权服务器（RFC 8414 发现 + RFC 7591
            // 动态注册，docs/mcp-oauth.md）；上游在此处查询的 /api/v1/auths 在本部署不存在，
            // 直接返回受支持的 oauth 提供方列表，使 MCP 编辑页的 OAuth 认证方式可用。
            this.auths = {oauths: ["kestra-oidc"]}
            return Promise.resolve(this.auths)
        },
    },
})
