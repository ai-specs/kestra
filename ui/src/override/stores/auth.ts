import {defineStore} from "pinia"

export class Me {
    hasAny(_permission: any, _namespace?: any) {
        return true
    }


    hasAnyAction(_permission: any, _action: any, _namespace?: any) {
        return true
    }


    isAllowed(_permission: any, _action: any, _namespace: any) {
        return true
    }


    isAllowedGlobal(_permission: any, _action: any) {
        return true
    }


    hasAnyActionOnAnyNamespace(_permission: any, _action: any) {
        return true
    }

    hasAnyRole() {
        return true
    }

    getNamespacesForAction(_permission: any, _action: any): string[] {
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
        loadAuths(_options: any): Promise<AuthMethods | undefined> {
            // dsh fork：MCP OAuth 走内置 kestra-oidc 授权服务器（RFC 8414 发现 + RFC 7591
            // 动态注册，docs/mcp-oauth.md）；上游在此处查询的 /api/v1/auths 在本部署不存在，
            // 直接返回受支持的 oauth 提供方列表，使 MCP 编辑页的 OAuth 认证方式可用。
            this.auths = {oauths: ["kestra-oidc"]}
            return Promise.resolve(this.auths)
        },
    },
})
